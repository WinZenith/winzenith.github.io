Provider Recommendations (prioritized)

1. Microsoft Update Catalog - authoritative central source for Windows drivers.
2. NVIDIA - GPU drivers (GeForce, Quadro).
3. Intel - chipsets, integrated graphics, NICs, storage.
4. AMD - Radeon GPUs and chipset drivers.
5. Realtek - audio and NIC drivers.
6. Broadcom / Qualcomm - Wi‑Fi, Bluetooth, NICs.
7. OEM feeds: HP, Dell, Lenovo - model-specific packages.
8. Peripherals: Logitech, Synaptics, Wacom, Epson - mice, touchpads, tablets, printers.

Integration guidance
- Prefer official APIs and signed package metadata.
- Verify publisher via Authenticode signatures and cert thumbprints.
- Use hardware IDs (PCI/USB/HID) to map drivers to devices.
- Respect ToS; rate-limit and cache vendor data.
