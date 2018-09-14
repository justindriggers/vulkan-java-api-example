package com.justindriggers.example.renderer;

public interface Renderer extends AutoCloseable {

    void renderFrame();

    void refresh();
}
