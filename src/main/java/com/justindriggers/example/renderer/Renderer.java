package com.justindriggers.example.renderer;

public interface Renderer extends AutoCloseable {

    void resize(final int width, final int height);

    void renderFrame();
}
