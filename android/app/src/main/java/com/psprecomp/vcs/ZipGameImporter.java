package com.psprecomp.vcs;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports a user-provided ZIP of their own PSP_GAME data into private app storage.
 * Supports archives rooted at PSP_GAME/, VCS/PSP_GAME/, or with SYSDIR/ and USRDIR/
 * directly at the archive root. Files are staged and validated before replacing
 * an existing import.
 */
final class ZipGameImporter {
    interface ProgressListener {
        void onProgress(String relativePath);
    }

    private ZipGameImporter() {}

    static void importZip(ContentResolver resolver, Uri zipUri, File destination,
                          ProgressListener listener) throws IOException {
        File parent = destination.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
            throw new IOException("No se pudo preparar el almacenamiento privado.");
        }

        File staging = new File(parent, "PSP_GAME.importing");
        File backup = new File(parent, "PSP_GAME.previous");
        deleteRecursively(staging);
        deleteRecursively(backup);
        if (!staging.mkdirs()) throw new IOException("No se pudo crear la carpeta temporal.");

        boolean installed = false;
        try (InputStream raw = resolver.openInputStream(zipUri)) {
            if (raw == null) throw new IOException("No se pudo abrir el ZIP seleccionado.");
            try (ZipInputStream zip = new ZipInputStream(raw)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String path = normalize(entry.getName());
                    if (path.isEmpty()) {
                        zip.closeEntry();
                        continue;
                    }

                    String relative = stripGameRoot(path);
                    if (relative == null || relative.isEmpty()) {
                        zip.closeEntry();
                        continue;
                    }
                    File output = safeOutput(staging, relative);
                    if (entry.isDirectory() || path.endsWith("/")) {
                        if (!output.exists() && !output.mkdirs()) {
                            throw new IOException("No se pudo crear: " + relative);
                        }
                    } else {
                        File outParent = output.getParentFile();
                        if (outParent != null && !outParent.exists() && !outParent.mkdirs()) {
                            throw new IOException("No se pudo crear la carpeta de: " + relative);
                        }
                        try (FileOutputStream out = new FileOutputStream(output)) {
                            byte[] buffer = new byte[64 * 1024];
                            int count;
                            while ((count = zip.read(buffer)) != -1) {
                                out.write(buffer, 0, count);
                            }
                            out.flush();
                        }
                        if (listener != null) listener.onProgress(relative);
                    }
                    zip.closeEntry();
                }
            }

            validate(staging);
            if (destination.exists() && !destination.renameTo(backup)) {
                throw new IOException("No se pudo guardar temporalmente la instalación anterior.");
            }
            if (!staging.renameTo(destination)) {
                if (backup.exists()) backup.renameTo(destination);
                throw new IOException("No se pudo finalizar la importación.");
            }
            installed = true;
            deleteRecursively(backup);
        } finally {
            if (!installed) {
                deleteRecursively(staging);
                if (!destination.exists() && backup.exists()) backup.renameTo(destination);
            }
        }
    }

    private static String normalize(String path) throws IOException {
        if (path == null) return "";
        String value = path.replace('\\', '/');
        while (value.startsWith("./")) value = value.substring(2);
        if (value.startsWith("/") || value.matches("^[A-Za-z]:.*")) {
            throw new IOException("El ZIP contiene una ruta no válida.");
        }
        for (String part : value.split("/")) {
            if (part.equals("..")) throw new IOException("El ZIP contiene una ruta insegura.");
        }
        return value;
    }

    /** Returns the path inside PSP_GAME, or null for unrelated archive files. */
    private static String stripGameRoot(String path) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equalsIgnoreCase("PSP_GAME")) {
                if (i + 1 >= parts.length) return "";
                StringBuilder result = new StringBuilder();
                for (int j = i + 1; j < parts.length; j++) {
                    if (result.length() > 0) result.append('/');
                    result.append(parts[j]);
                }
                return result.toString();
            }
        }
        if (parts.length > 0 &&
            (parts[0].equalsIgnoreCase("SYSDIR") || parts[0].equalsIgnoreCase("USRDIR"))) {
            return path;
        }
        return null;
    }

    private static File safeOutput(File root, String relative) throws IOException {
        File target = new File(root, relative);
        String rootPath = root.getCanonicalPath() + File.separator;
        String targetPath = target.getCanonicalPath();
        if (!targetPath.startsWith(rootPath)) {
            throw new IOException("El ZIP contiene una ruta que sale de la carpeta del juego.");
        }
        return target;
    }

    private static void validate(File root) throws IOException {
        File elf = new File(root, "SYSDIR/EBOOT_DECRYPTED.ELF");
        File movies = new File(root, "USRDIR/RUNDATA/PSP/MOVIES");
        if (!elf.isFile() || !new File(movies, "LOGO.PMF").isFile() ||
            !new File(movies, "TITLES.PMF").isFile()) {
            throw new IOException("El ZIP no contiene una copia completa. Debe incluir SYSDIR y USRDIR dentro de PSP_GAME.");
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
