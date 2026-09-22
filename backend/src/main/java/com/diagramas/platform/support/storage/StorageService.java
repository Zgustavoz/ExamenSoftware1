package com.diagramas.platform.support.storage;

/** Almacenamiento de archivos (XMI, ZIP de código, adjuntos). S3 en producción, disco local en desarrollo. */
public interface StorageService {

    /** @return la clave bajo la que quedó guardado el objeto */
    String put(String key, byte[] content, String contentType);
}
