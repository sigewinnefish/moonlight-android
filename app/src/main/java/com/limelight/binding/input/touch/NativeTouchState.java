package com.limelight.binding.input.touch;

import com.limelight.nvstream.jni.MoonBridge;

/** UI-thread-owned protocol state. Android pointer IDs are in [0, 31], independent of indexes. */
public final class NativeTouchState {
    public interface Sender {
        int send(byte type, int id, float x, float y, float pressure);
    }

    private final Sender sender;
    private long active;
    private boolean failed;

    public NativeTouchState(Sender sender) {
        this.sender = sender;
    }

    public void begin() {
        cancel();
        failed = false;
    }

    public boolean down(int id, float x, float y, float pressure) {
        if (failed || id < 0 || id > 31) {
            return true; // Never switch to mouse partway through a native gesture.
        }
        long bit = 1L << id;
        if ((active & bit) != 0) {
            send(MoonBridge.LI_TOUCH_EVENT_CANCEL, id, 0, 0, 0);
        }
        if (failed) {
            return true;
        }
        int result = sender.send(MoonBridge.LI_TOUCH_EVENT_DOWN, id, x, y, pressure);
        if (result == MoonBridge.LI_ERR_UNSUPPORTED && active == 0) {
            return false;
        }
        if (result == 0) {
            active |= bit;
        }
        else {
            fail();
        }
        return true;
    }

    public void move(int id, float x, float y, float pressure) {
        if (isActive(id)) {
            send(MoonBridge.LI_TOUCH_EVENT_MOVE, id, x, y, pressure);
        }
    }

    public void up(int id, float x, float y, float pressure, boolean cancelled) {
        if (isActive(id)) {
            send(cancelled ? MoonBridge.LI_TOUCH_EVENT_CANCEL : MoonBridge.LI_TOUCH_EVENT_UP,
                    id, x, y, pressure);
            active &= ~(1L << id);
        }
    }

    /** Defensively cancel a pointer missing from a subsequent Android event. */
    public void reconcile(long present) {
        long missing = active & ~present;
        for (int id = 0; id < 32; id++) {
            if ((missing & (1L << id)) != 0) {
                up(id, 0, 0, 0, true);
            }
        }
    }

    public void cancel() {
        if (active != 0) {
            // CANCEL_ALL, rather than UP, preserves the protocol's cancellation semantics.
            sender.send(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, 0, 0, 0, 0);
        }
        active = 0;
        failed = true; // Ignore late MOVE/UP until a fresh ACTION_DOWN.
    }

    private boolean isActive(int id) {
        return !failed && id >= 0 && id < 32 && (active & (1L << id)) != 0;
    }

    private void send(byte type, int id, float x, float y, float pressure) {
        if (sender.send(type, id, x, y, pressure) != 0) {
            fail();
        }
    }

    private void fail() {
        // A failed enqueue must never cause the remainder of this gesture to become mouse input.
        sender.send(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, 0, 0, 0, 0);
        active = 0;
        failed = true;
    }

    public static float normalize(float position, float origin, float size) {
        return Math.max(0, Math.min(1, (position - origin) / size));
    }
}
