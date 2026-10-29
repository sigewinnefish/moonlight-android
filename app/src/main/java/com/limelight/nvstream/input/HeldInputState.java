package com.limelight.nvstream.input;

import java.util.BitSet;
import java.util.Map;
import java.util.TreeMap;

/** Held key/button cleanup when a live stream loses its local input window.
 * The caller serializes recording, sending and release on this object's monitor. */
public final class HeldInputState {
    public interface ReleaseListener {
        void keyUp(short code, byte flags);
        void mouseUp(byte button);
    }
    private final TreeMap<Short, Byte> keys = new TreeMap<>();
    private final BitSet mouse = new BitSet(256);

    public void key(short code, byte flags, boolean down) {
        if (down) keys.put(code, flags); else keys.remove(code);
    }
    public void mouse(byte button, boolean down) { mouse.set(button & 0xff, down); }

    public void release(ReleaseListener listener) {
        for (Map.Entry<Short, Byte> key : keys.entrySet()) listener.keyUp(key.getKey(), key.getValue());
        keys.clear();
        for (int button = mouse.nextSetBit(0); button >= 0; button = mouse.nextSetBit(button+1)) listener.mouseUp((byte)button);
        mouse.clear();
    }
}
