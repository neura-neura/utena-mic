using System.Threading.Channels;

namespace UtenaMic;

internal sealed class MuteSession(VirtualCable? cable)
{
    private readonly object sync = new();
    private readonly Channel<byte> updates = Channel.CreateBounded<byte>(new BoundedChannelOptions(1)
    {
        FullMode = BoundedChannelFullMode.DropOldest,
        SingleReader = true
    });
    private volatile bool muted;
    internal bool Muted => muted;

    internal void Toggle()
    {
        lock (sync)
        {
            muted = !muted;
            cable?.SetMuted(muted);
            updates.Writer.TryWrite(muted ? (byte)1 : (byte)0);
        }
    }

    internal async Task Send(Stream stream, CancellationToken token)
    {
        byte[] packet = new byte[1];
        await foreach (byte state in updates.Reader.ReadAllAsync(token))
        {
            packet[0] = state;
            await stream.WriteAsync(packet, token);
        }
    }
}
