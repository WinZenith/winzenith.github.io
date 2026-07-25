module com.winzenith {
    requires javafx.controls;
    requires javafx.graphics;
    requires javafx.swing;
    requires javafx.base;

    requires atlantafx.base;

    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.annotation;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.datatype.jsr310;

    requires com.sun.jna;
    requires com.sun.jna.platform;

    requires java.desktop;
    requires java.net.http;
    requires java.logging;

    opens com.sbtools to javafx.controls;
    opens com.sbtools.ui to javafx.controls, javafx.graphics;
    opens com.sbtools.cleaner to javafx.controls;
    opens com.sbtools.cleaner.impl to javafx.controls;
    opens com.sbtools.backup to javafx.controls;
    opens com.sbtools.duplicates to javafx.controls;
    opens com.sbtools.drivers to javafx.controls;
    opens com.sbtools.drivers.model to com.fasterxml.jackson.databind;
    opens com.sbtools.drivers.catalog to com.fasterxml.jackson.databind;
    opens com.sbtools.software to javafx.controls, com.fasterxml.jackson.databind;
    opens com.sbtools.settings to com.fasterxml.jackson.databind;
    opens com.sbtools.startup to javafx.controls, com.fasterxml.jackson.databind;
    opens com.sbtools.systeminfo to javafx.controls, com.fasterxml.jackson.databind;
    opens com.sbtools.netoptimizer to javafx.controls, com.fasterxml.jackson.databind;
    opens com.sbtools.defrag to javafx.controls;
    opens com.sbtools.shredder to javafx.controls;
    opens com.sbtools.uninstaller to javafx.controls;
    opens com.sbtools.browserext to javafx.controls;
    opens com.sbtools.diskhealth to javafx.controls, com.fasterxml.jackson.databind;
    opens com.sbtools.license to javafx.controls;
    opens com.sbtools.update to javafx.controls;
    opens com.sbtools.util to javafx.controls;

    exports com.sbtools;
    exports com.sbtools.ui;
    exports com.sbtools.cleaner;
    exports com.sbtools.cleaner.impl;
    exports com.sbtools.backup;
    exports com.sbtools.duplicates;
    exports com.sbtools.drivers;
    exports com.sbtools.drivers.model;
    exports com.sbtools.drivers.catalog;
    exports com.sbtools.software;
    exports com.sbtools.settings;
    exports com.sbtools.startup;
    exports com.sbtools.systeminfo;
    exports com.sbtools.netoptimizer;
    exports com.sbtools.defrag;
    exports com.sbtools.shredder;
    exports com.sbtools.uninstaller;
    exports com.sbtools.browserext;
    exports com.sbtools.diskhealth;
    exports com.sbtools.license;
    exports com.sbtools.update;
    exports com.sbtools.util;
}
