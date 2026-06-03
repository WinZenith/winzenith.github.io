package com.sbtools.backup;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class BackupIndex {

    private List<DriverBackupEntry> entries = new ArrayList<>();

    public List<DriverBackupEntry> getEntries() {
        return entries;
    }

    public void setEntries(List<DriverBackupEntry> entries) {
        this.entries = entries != null ? entries : new ArrayList<>();
    }
}
