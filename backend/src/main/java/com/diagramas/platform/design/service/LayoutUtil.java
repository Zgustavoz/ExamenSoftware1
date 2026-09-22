package com.diagramas.platform.design.service;

/** Auto-posicionamiento en grilla para clases sin coordenadas (IA, XMI sin layout). */
public final class LayoutUtil {

    private static final int COLUMNS = 4;
    private static final int CELL_W = 260;
    private static final int CELL_H = 220;
    private static final int MARGIN = 40;

    private LayoutUtil() {}

    /** Posición de la n-ésima clase (0-based). */
    public static int[] gridPosition(int index) {
        int col = index % COLUMNS;
        int row = index / COLUMNS;
        return new int[] {MARGIN + col * CELL_W, MARGIN + row * CELL_H};
    }
}
