package com.psprecomp.vcs;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Copies a user-selected PSP_GAME directory into the app's private storage.
 * The selected directory is read through Android's Storage Access Framework;
 * no broad external-storage permission is required.
 */
final class GameImporter {
    interface ProgressListener {
        void onProgress(String relativePath);
    }

    private static final String[] CHILD_COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE
    };

    private GameImporter() {}

    static void copyPspGame(ContentResolver resolver, Uri treeUri, File destination,
                            ProgressListener listener) throws IOException {
        String rootId;
        try {
            rootId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (RuntimeException e) {
            throw new IOException("No se pudo leer la carpeta seleccionada.", e);
        }

        String rootMime = queryMimeType(resolver, treeUri, rootId);
        if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(rootMime)) {
            throw new IOException("Debes seleccionar la carpeta PSP_GAME, no un archivo.");
        }

        if (destination.exists() && !destination.isDirectory()) {
            throw new IOException("El destino de importación no es una carpeta.");
        }
        if (!destination.exists() && !destination.mkdirs()) {
            throw new IOException("No se pudo crear la carpeta privada del juego.");
        }

        copyChildren(resolver, treeUri, rootId, destination, "", listener);
    }

    private static String queryMimeType(ContentResolver resolver, Uri treeUri, String documentId)
            throws IOException {
        Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
        try (Cursor cursor = resolver.query(documentUri,
                new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE},
                null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                throw new IOException("No se pudo acceder a la carpeta seleccionada.");
            }
            return cursor.getString(0);
        } catch (SecurityException e) {
            throw new IOException("Android no concedió acceso a la carpeta seleccionada.", e);
        }
    }

    private static void copyChildren(ContentResolver resolver, Uri treeUri, String parentId,
                                     File destination, String relativePath,
                                     ProgressListener listener) throws IOException {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        try (Cursor cursor = resolver.query(childrenUri, CHILD_COLUMNS, null, null, null)) {
            if (cursor == null) {
                throw new IOException("No se pudo leer el contenido de una carpeta del juego.");
            }

            int idColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_MIME_TYPE);

            while (cursor.moveToNext()) {
                String documentId = cursor.getString(idColumn);
                String displayName = safeName(cursor.getString(nameColumn));
                String mimeType = cursor.getString(mimeColumn);
                if (displayName.isEmpty()) continue;

                File target = new File(destination, displayName);
                String childPath = relativePath.isEmpty()
                    ? displayName : relativePath + "/" + displayName;

                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                    if (target.exists() && !target.isDirectory()) {
                        throw new IOException("Conflicto de archivos en: " + childPath);
                    }
                    if (!target.exists() && !target.mkdirs()) {
                        throw new IOException("No se pudo crear: " + childPath);
                    }
                    copyChildren(resolver, treeUri, documentId, target, childPath, listener);
                } else {
                    copyFile(resolver, treeUri, documentId, target, childPath, listener);
                }
            }
        } catch (SecurityException e) {
            throw new IOException("Se perdió el permiso para leer los archivos del juego.", e);
        }
    }

    private static void copyFile(ContentResolver resolver, Uri treeUri, String documentId,
                                 File destination, String relativePath,
                                 ProgressListener listener) throws IOException {
        if (destination.exists() && destination.isDirectory()) {
            throw new IOException("Conflicto de archivos en: " + relativePath);
        }

        Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
        try (InputStream input = resolver.openInputStream(documentUri)) {
            if (input == null) {
                throw new IOException("No se pudo abrir: " + relativePath);
            }
            File parent = destination.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("No se pudo crear la carpeta de: " + relativePath);
            }
            try (OutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                output.flush();
            }
        } catch (SecurityException e) {
            throw new IOException("Android bloqueó la lectura de: " + relativePath, e);
        }

        if (listener != null) listener.onProgress(relativePath);
    }

    private static String safeName(String name) {
        if (name == null) return "";
        String safe = name.replace('/', '_').replace('\\\\', '_').trim();
        if (safe.isEmpty() || safe.equals(".") || safe.equals("..")) return "";
        return safe;
    }
}
