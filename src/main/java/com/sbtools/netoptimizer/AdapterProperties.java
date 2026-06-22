package com.sbtools.netoptimizer;

import java.util.Map;

public record AdapterProperties(
        String adapterName,
        Map<String, String> properties
) {}
