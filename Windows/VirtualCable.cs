using System.Buffers.Binary;
using System.Runtime.InteropServices;

namespace UtenaMic;

internal sealed class VirtualCable : IDisposable
{
    private const int SampleRate = 48000;
    private const int SamplesPerMs = SampleRate / 1000;
    private const int StartSamples = 45 * SamplesPerMs;
    private const int TargetSamples = 42 * SamplesPerMs;
    private const int MaxSamples = 85 * SamplesPerMs;
    private const int FadeSamples = 2 * SamplesPerMs;
    private const uint EventCallback = 0x00040000;
    private const uint AutoConvertPcm = 0x80000000;
    private const uint SrcDefaultQuality = 0x08000000;
    private const long RequestedBufferDuration = 400_000; // 40 ms in 100 ns units
    private const uint TargetPadding = 25 * SamplesPerMs;

    private readonly string endpointId;
    private readonly Action<string> trace;
    private readonly Thread render;
    private readonly AutoResetEvent ready = new(false);
    private readonly ManualResetEventSlim initialized = new(false);
    private readonly object sync = new();
    private readonly short[] pending = new short[MaxSamples];
    private int readIndex, available;
    private long droppedSamples, underruns;
    private double position, rate = 1;
    private bool started, rebuffering = true, discontinuity;
    private short lastSample;
    private volatile bool stopping;
    private volatile IOException? failure;

    [StructLayout(LayoutKind.Sequential, Pack = 2)]
    private struct WaveFormat
    {
        public ushort Tag, Channels;
        public uint SamplesPerSecond, BytesPerSecond;
        public ushort Align, Bits, Extra;
    }

    [ComImport, Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioClient
    {
        void Initialize(int shareMode, uint flags, long duration, long periodicity, in WaveFormat format, IntPtr session);
        void GetBufferSize(out uint frames);
        void GetStreamLatency();
        void GetCurrentPadding(out uint frames);
        void IsFormatSupported();
        void GetMixFormat();
        void GetDevicePeriod();
        void Start();
        void Stop();
        void Reset();
        void SetEventHandle(IntPtr handle);
        void GetService(in Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
    }

    [ComImport, Guid("F294ACFC-3146-4483-A7BF-ADDCA7C260E2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioRenderClient
    {
        void GetBuffer(uint frames, out IntPtr data);
        void ReleaseBuffer(uint frames, uint flags);
    }

    private VirtualCable(string endpointId, Action<string> trace)
    {
        this.endpointId = endpointId;
        this.trace = trace;
        render = new Thread(Render) { IsBackground = true, Name = "Utena Mic audio", Priority = ThreadPriority.AboveNormal };
        render.Start();
    }

    public static VirtualCable? TryOpen(Action<string> trace, out string report)
    {
        var id = VacCaptureName.RenderId();
        if (id == null)
        {
            report = "Audio recibido · falta Line 1 (Virtual Audio Cable).";
            return null;
        }
        var cable = new VirtualCable(id, trace);
        cable.initialized.Wait();
        if (cable.failure is { } error)
        {
            trace(error.Message);
            cable.Dispose();
            report = "Audio recibido · no se pudo abrir la salida virtual.";
            return null;
        }
        report = "Conectado · selecciona Utena Mic en Windows.";
        return cable;
    }

    public void Enqueue(ReadOnlySpan<byte> pcm)
    {
        if (failure is { } error) throw error;
        lock (sync)
        {
            int count = pcm.Length / 2;
            if (available + count > pending.Length)
            {
                // A delayed TCP burst must not push live audio further behind.
                int discard = available + count - pending.Length;
                Advance(discard);
                droppedSamples += discard;
                discontinuity = true;
            }
            int writeIndex = (readIndex + available) % pending.Length;
            for (int i = 0; i < count; i++)
            {
                pending[writeIndex] = BinaryPrimitives.ReadInt16LittleEndian(pcm.Slice(i * 2, 2));
                writeIndex = (writeIndex + 1) % pending.Length;
            }
            available += count;
            if (!started && available >= StartSamples) ready.Set();
        }
    }

    private void Advance(int samples)
    {
        readIndex = (readIndex + samples) % pending.Length;
        available -= samples;
    }

    private void Render()
    {
        IAudioClient? audio = null;
        IAudioRenderClient? output = null;
        try
        {
            audio = (IAudioClient)AudioDefaults.Activate(endpointId, typeof(IAudioClient).GUID);
            var format = new WaveFormat
            {
                Tag = 1, Channels = 2, SamplesPerSecond = SampleRate,
                BytesPerSecond = SampleRate * 4, Align = 4, Bits = 16
            };
            audio.Initialize(0, EventCallback | AutoConvertPcm | SrcDefaultQuality, RequestedBufferDuration, 0,
                in format, IntPtr.Zero);
            audio.SetEventHandle(ready.SafeWaitHandle.DangerousGetHandle());
            audio.GetBufferSize(out uint bufferFrames);
            var renderId = typeof(IAudioRenderClient).GUID;
            audio.GetService(in renderId, out object service);
            output = (IAudioRenderClient)service;
            var stereo = new short[checked((int)bufferFrames * 2)];
            initialized.Set();

            while (!stopping)
            {
                lock (sync)
                {
                    if (available >= StartSamples)
                    {
                        started = true;
                        break;
                    }
                }
                ready.WaitOne();
            }
            if (stopping) return;

            // Keep room in the endpoint buffer without keeping extra audio queued.
            uint targetPadding = Math.Min(bufferFrames, TargetPadding);
            Write(output, stereo, targetPadding);
            audio.Start();
            while (!stopping)
            {
                if (!ready.WaitOne(1000)) throw new IOException("La salida virtual dejó de responder.");
                if (stopping) break;
                audio.GetCurrentPadding(out uint padding);
                if (padding < targetPadding) Write(output, stereo, targetPadding - padding);
            }
        }
        catch (Exception error) when (error is COMException or IOException)
        {
            failure = new IOException($"Error de la salida virtual: {error.Message}", error);
        }
        finally
        {
            initialized.Set();
            if (audio != null && started)
                try { audio.Stop(); } catch (COMException) { }
            if (output != null) Marshal.ReleaseComObject(output);
            if (audio != null) Marshal.ReleaseComObject(audio);
        }
    }

    private void Write(IAudioRenderClient output, short[] stereo, uint frames)
    {
        lock (sync) Fill(stereo, (int)frames);
        output.GetBuffer(frames, out var target);
        Marshal.Copy(stereo, 0, target, (int)frames * 2);
        output.ReleaseBuffer(frames, 0);
    }

    private void Fill(short[] stereo, int frames)
    {
        if (rebuffering)
        {
            if (available < StartSamples)
            {
                Array.Clear(stereo, 0, frames * 2);
                return;
            }
            rebuffering = false;
            discontinuity = true;
            rate = 1;
        }

        // The Android capture clock and Windows playback clock can drift independently.
        double desiredRate = 1 + Math.Clamp((available - TargetSamples) * 0.000004, -0.005, 0.005);
        rate += (desiredRate - rate) * 0.01;
        bool fadingIn = discontinuity;
        if (fadingIn) position = 0;
        discontinuity = false;
        int rendered = Math.Min(frames, Math.Max(0, (int)Math.Floor((available - 1 - position) / rate)));
        bool fadingOut = rendered < frames;
        short previous = lastSample;
        for (int i = 0; i < rendered; i++)
        {
            int offset = (int)position;
            short left = pending[(readIndex + offset) % pending.Length];
            short right = pending[(readIndex + offset + 1) % pending.Length];
            double sample = left + (position - offset) * (right - left);
            if (fadingIn && i < FadeSamples) sample = previous + (sample - previous) * i / FadeSamples;
            if (fadingOut) sample *= Math.Min(1.0, (rendered - 1 - i) / (double)FadeSamples);
            stereo[i * 2] = stereo[i * 2 + 1] = (short)sample;
            position += rate;
        }
        if (fadingOut)
        {
            Array.Clear(stereo, rendered * 2, (frames - rendered) * 2);
            // If the previous block ended at a nonzero value, fade it to silence.
            if (rendered == 0)
                for (int i = 0; i < Math.Min(FadeSamples, frames); i++)
                    stereo[i * 2] = stereo[i * 2 + 1] = (short)(previous * (FadeSamples - 1 - i) / FadeSamples);
            Advance(available);
            position = 0;
            rebuffering = true;
            underruns++;
        }
        else
        {
            int consumed = (int)position;
            Advance(consumed);
            position -= consumed;
        }
        lastSample = stereo[frames * 2 - 1];
    }

    public void Dispose()
    {
        stopping = true;
        ready.Set();
        render.Join();
        initialized.Dispose();
        ready.Dispose();
        trace($"Salida PCM: {underruns} interrupciones; {droppedSamples} muestras descartadas");
    }
}
