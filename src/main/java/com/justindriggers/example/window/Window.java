package com.justindriggers.example.window;

import java.io.Closeable;

public interface Window extends Closeable {

    int getWidth();

    int getHeight();

    long getHandle();
}
