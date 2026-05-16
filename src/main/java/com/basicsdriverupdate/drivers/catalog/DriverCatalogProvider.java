package com.basicsdriverupdate.drivers.catalog;

import com.basicsdriverupdate.drivers.model.DriverUpdateCandidate;
import com.basicsdriverupdate.drivers.model.InstalledDriver;

import java.util.List;

public interface DriverCatalogProvider {

    String id();

    List<DriverUpdateCandidate> findUpdates(List<InstalledDriver> installed);
}
