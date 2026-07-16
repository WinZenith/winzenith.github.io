package com.sbtools.backup;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class RegistryBackupRow {
    private final StringProperty filename;
    private final StringProperty date;
    private final StringProperty size;

    public RegistryBackupRow(String filename, String date, String size) {
        this.filename = new SimpleStringProperty(filename);
        this.date = new SimpleStringProperty(date);
        this.size = new SimpleStringProperty(size);
    }

    public StringProperty filenameProperty() { return filename; }
    public StringProperty dateProperty() { return date; }
    public StringProperty sizeProperty() { return size; }

    public String getFilename() { return filename.get(); }
    public String getDate() { return date.get(); }
    public String getSize() { return size.get(); }
}
