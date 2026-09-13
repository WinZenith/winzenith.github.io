package com.sbtools.ui;

import com.sbtools.drivers.model.DriverRow;
import javafx.collections.ObservableList;

import java.util.List;
import java.util.Map;

/** Transactional rollback of Drivers table rows to a pre-scan snapshot. */
final class DriversScanSnapshot {

    private DriversScanSnapshot() {
    }

    static void restore(ObservableList<DriverRow> outdatedRows,
                        ObservableList<DriverRow> upToDateRows,
                        List<DriverRow> snapshotOutdated,
                        List<DriverRow> snapshotUpToDate,
                        Map<DriverRow, ?> installCells) {
        outdatedRows.setAll(snapshotOutdated == null ? List.of() : snapshotOutdated);
        upToDateRows.setAll(snapshotUpToDate == null ? List.of() : snapshotUpToDate);
        if (installCells != null) {
            installCells.clear();
        }
    }
}
