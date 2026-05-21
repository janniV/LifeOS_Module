package com.lifeos.filemanager;

import java.nio.file.Path;

// Ein einzelner Eintrag (Datei oder Ordner) innerhalb der LifeOS-Ordnerstruktur.
// Pfade bleiben absolute LifeOS-Pfade; sie verlassen die verwaltete Struktur nicht.
public final class FileEntry {

    private final Path path;
    private final boolean directory;
    private final long size;
    private final long lastModified;

    public FileEntry(Path path, boolean directory, long size, long lastModified) {
        this.path = path;
        this.directory = directory;
        this.size = size;
        this.lastModified = lastModified;
    }

    public Path getPath() {
        return path;
    }

    // Der reine Datei- oder Ordnername wird zurueckgegeben.
    public String getName() {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    public boolean isDirectory() {
        return directory;
    }

    public long getSize() {
        return size;
    }

    public long getLastModified() {
        return lastModified;
    }
}
