package com.justindriggers.example.window;

public interface Window extends AutoCloseable {

    int getWidth();

    int getHeight();

    long getHandle();
}
