package com.justindriggers.example.renderer;

import java.io.Closeable;

public interface Renderer extends Closeable {

    void renderFrame();

    void refresh();
}
