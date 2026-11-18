using System.Buffers.Binary;
using System.Diagnostics;

namespace UtenaMic;

// Accessed under the render/capture lock in VirtualCable.
internal sealed class AdaptivePcmBuffer
{
    private const int SamplesPerMs = 48;
    private const int MinimumReserve = 54 * SamplesPerMs;
    private const int MaximumReserve = 450 * SamplesPerMs;
    private const int RecoveryMargin = 16 * SamplesPerMs;
    private const int Capacity = 4000 * SamplesPerMs;
    private const int SplicePeriod = 20 * SamplesPerMs;
    private const int Overlap = 6 * SamplesPerMs;
    private const int SearchRadius = SamplesPerMs;
    private const int Fade = 3 * SamplesPerMs;
    private readonly short[] samples = new short[Capacity];

    private int head, count, previousPacket, fadeRemaining;
    private int spliceCountdown, spliceShift, spliceProgress;
    private long previousArrival, lastIncrease;
    private double reserve = MinimumReserve, position, rate = 1;
    private short last;
    private bool buffering = true, muted;

    internal bool CanStart => count >= reserve;
    internal long Underruns { get; private set; }
    internal long DroppedSamples { get; private set; }
    internal long CompressedSamples { get; private set; }
    internal int PeakReserveMs { get; private set; } = MinimumReserve / SamplesPerMs;

    internal void SetMuted(bool value)
    {
        muted = value;
        if (!muted) return;
        Array.Clear(samples);
        last = 0;
    }

    internal void Enqueue(ReadOnlySpan<byte> pcm)
    {
        int frames = pcm.Length / sizeof(short);
        long now = Stopwatch.GetTimestamp();
        if (previousArrival != 0)
        {
            double interval = Stopwatch.GetElapsedTime(previousArrival, now).TotalMilliseconds;
            double lateSamples = interval * SamplesPerMs - previousPacket;
            // TCP can deliver several late packets together. Retain headroom for the
            // observed pause and lower it slowly once arrivals have been steady.
            if (lateSamples > 8 * SamplesPerMs && MinimumReserve + lateSamples > reserve)
                IncreaseReserve(MinimumReserve + lateSamples, now);
            else if (Stopwatch.GetElapsedTime(lastIncrease, now).TotalSeconds > 8)
                reserve = Math.Max(MinimumReserve, reserve - Math.Min(interval, 30) * SamplesPerMs * 0.010);
        }
        previousArrival = now;
        previousPacket = frames;

        int excess = count + frames - samples.Length;
        if (excess > 0)
        {
            Advance(excess);
            DroppedSamples += excess;
            ResetSplice();
            position = 0;
            fadeRemaining = Fade;
        }

        int tail = (head + count) % samples.Length;
        for (int i = 0; i < frames; i++)
        {
            samples[tail] = muted ? (short)0 : BinaryPrimitives.ReadInt16LittleEndian(pcm.Slice(i * 2, 2));
            if (++tail == samples.Length) tail = 0;
        }
        count += frames;
    }

    private void IncreaseReserve(double value, long now)
    {
        reserve = Math.Min(MaximumReserve, value);
        lastIncrease = now;
        PeakReserveMs = Math.Max(PeakReserveMs, (int)Math.Ceiling(reserve / SamplesPerMs));
    }

    internal void Fill(Span<short> stereo)
    {
        int frames = stereo.Length / 2;
        if (buffering)
        {
            double readyAt = reserve + (Underruns == 0 ? 0 : RecoveryMargin);
            if (count < readyAt)
            {
                stereo.Clear();
                return;
            }
            buffering = false;
            fadeRemaining = Fade;
        }

        double drift = Math.Clamp((count - reserve) * 0.000003, -0.004, 0.004);
        rate += ((1 + drift) - rate) * 0.012;
        if (count < Math.Ceiling(position + frames * rate) + spliceShift + 2)
        {
            // Keep the unplayed tail in the ring and resume it after refilling.
            // A single short fade avoids clicks while the live source is unavailable.
            stereo.Clear();
            for (int i = 0; i < Math.Min(frames, Fade); i++)
                stereo[i * 2] = stereo[i * 2 + 1] = (short)(last * (Fade - i - 1) / Fade);
            last = 0;
            buffering = true;
            Underruns++;
            ResetSplice();
            IncreaseReserve(reserve + 32 * SamplesPerMs, Stopwatch.GetTimestamp());
            return;
        }

        for (int i = 0; i < frames; i++)
        {
            if (spliceShift == 0 && spliceCountdown-- <= 0)
                StartSplice(frames - i);

            double sample = Interpolate(position);
            if (spliceShift > 0)
            {
                double mix = (spliceProgress + 1) / (double)Overlap;
                mix = mix * mix * (3 - 2 * mix);
                sample += (Interpolate(position + spliceShift) - sample) * mix;
                if (++spliceProgress == Overlap)
                {
                    position += spliceShift;
                    CompressedSamples += spliceShift;
                    spliceShift = 0;
                }
            }
            if (fadeRemaining > 0) sample *= (Fade - fadeRemaining--) / (double)Fade;
            stereo[i * 2] = stereo[i * 2 + 1] = (short)sample;
            position += rate;
        }

        int consumed = (int)position;
        Advance(consumed);
        position -= consumed;
        last = stereo[^1];
    }

    private void StartSplice(int remainingFrames)
    {
        spliceCountdown = SplicePeriod;
        double excess = count - position - reserve - 22 * SamplesPerMs;
        if (excess < 0) return;

        // Compress at up to about 8% by overlapping nearby portions of the voice.
        // The match keeps the waveforms in phase while the pitch stays near normal.
        int wanted = Math.Clamp((int)(excess / 30), 12, 76);
        int first = Math.Max(4, wanted - SearchRadius);
        int lastShift = Math.Min(Math.Min(wanted + SearchRadius, 76), count - (int)position -
            Math.Max(Overlap, (int)Math.Ceiling(remainingFrames * rate)) - 3);
        double best = double.PositiveInfinity;
        for (int shift = first; shift <= lastShift; shift++)
        {
            double difference = 0, energy = 1;
            for (int j = 0; j < Overlap; j += 6)
            {
                double a = At((int)position + j);
                double b = At((int)position + j + shift);
                difference += (a - b) * (a - b);
                energy += a * a + b * b;
            }
            double score = difference / energy + Math.Abs(wanted - shift) * 0.003;
            if (score < best)
            {
                best = score;
                spliceShift = shift;
            }
        }
        spliceProgress = 0;
    }

    private double Interpolate(double offset)
    {
        int integer = (int)offset;
        short left = At(integer);
        return left + (At(integer + 1) - left) * (offset - integer);
    }

    private short At(int offset) => samples[(head + offset) % samples.Length];

    private void Advance(int frames)
    {
        head = (head + frames) % samples.Length;
        count -= frames;
    }

    private void ResetSplice()
    {
        spliceShift = 0;
        spliceProgress = 0;
        spliceCountdown = 0;
    }
}
