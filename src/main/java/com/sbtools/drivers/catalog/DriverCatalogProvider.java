package com.sbtools.drivers.catalog;

import com.sbtools.drivers.model.DriverUpdateCandidate;
import com.sbtools.drivers.model.InstalledDriver;

import java.util.List;

public interface DriverCatalogProvider {

    String id();

    List<DriverUpdateCandidate> findUpdates(List<InstalledDriver> installed);
}
