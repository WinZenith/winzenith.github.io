package com.sbtools.util;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinNT;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;

import java.io.File;

public class IconExtractor {

    public static Image extractIcon(String filePath) {
        if (filePath == null || filePath.isBlank()) return null;

        File file = new File(filePath);
        if (!file.exists()) return null;

        WinDef.HICON[] largeIcons = new WinDef.HICON[1];
        WinDef.HICON[] smallIcons = new WinDef.HICON[1];

        try {
            int count = Shell32.INSTANCE.ExtractIconEx(filePath, 0, largeIcons, smallIcons, 1);
            if (count <= 0) return null;

            WinDef.HICON hicon = largeIcons[0];
            if (hicon == null) {
                hicon = smallIcons[0];
            }
            if (hicon == null) return null;

            try {
                return hiconToImage(hicon);
            } finally {
                User32.INSTANCE.DestroyIcon(hicon);
                if (smallIcons[0] != null && smallIcons[0] != hicon) {
                    User32.INSTANCE.DestroyIcon(smallIcons[0]);
                }
            }
        } catch (Exception e) {
            cleanupIcons(largeIcons, smallIcons);
            return null;
        }
    }

    private static Image hiconToImage(WinDef.HICON hicon) {
        WinGDI.ICONINFO iconInfo = new WinGDI.ICONINFO();
        if (!User32.INSTANCE.GetIconInfo(hicon, iconInfo)) return null;

        try {
            if (iconInfo.hbmColor == null) return null;

            WinGDI.BITMAP bm = new WinGDI.BITMAP();
            if (GDI32.INSTANCE.GetObject(iconInfo.hbmColor, bm.size(), bm.getPointer()) == 0) return null;
            bm.read();

            int width = bm.bmWidth.intValue();
            int height = bm.bmHeight.intValue();
            if (width <= 0 || height <= 0) return null;

            WinGDI.BITMAPINFO bi = new WinGDI.BITMAPINFO();
            bi.bmiHeader.biSize = bi.bmiHeader.size();
            bi.bmiHeader.biWidth = width;
            bi.bmiHeader.biHeight = -height;
            bi.bmiHeader.biPlanes = 1;
            bi.bmiHeader.biBitCount = 32;
            bi.bmiHeader.biCompression = WinGDI.BI_RGB;

            int imageSize = width * height * 4;
            Memory colorBuffer = new Memory(imageSize);

            WinNT.HDC hdc = User32.INSTANCE.GetDC(null);
            WinNT.HDC memDC = GDI32.INSTANCE.CreateCompatibleDC(hdc);

            WinNT.HANDLE hColorHandle = new WinNT.HANDLE(iconInfo.hbmColor.getPointer());
            WinNT.HANDLE oldBitmap = GDI32.INSTANCE.SelectObject(memDC, hColorHandle);
            GDI32.INSTANCE.GetDIBits(memDC, iconInfo.hbmColor, 0, height, colorBuffer, bi, WinGDI.DIB_RGB_COLORS);
            GDI32.INSTANCE.SelectObject(memDC, oldBitmap);
            GDI32.INSTANCE.DeleteDC(memDC);
            User32.INSTANCE.ReleaseDC(null, hdc);

            WritableImage image = new WritableImage(width, height);
            PixelWriter pw = image.getPixelWriter();

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int offset = (y * width + x) * 4;
                    int b = colorBuffer.getByte(offset) & 0xFF;
                    int g = colorBuffer.getByte(offset + 1) & 0xFF;
                    int r = colorBuffer.getByte(offset + 2) & 0xFF;
                    int a = colorBuffer.getByte(offset + 3) & 0xFF;

                    int argb = (a << 24) | (r << 16) | (g << 8) | b;
                    pw.setArgb(x, y, argb);
                }
            }

            return image;
        } finally {
            if (iconInfo.hbmColor != null) GDI32.INSTANCE.DeleteObject(iconInfo.hbmColor);
            if (iconInfo.hbmMask != null) GDI32.INSTANCE.DeleteObject(iconInfo.hbmMask);
        }
    }

    private static void cleanupIcons(WinDef.HICON[] largeIcons, WinDef.HICON[] smallIcons) {
        if (largeIcons != null) {
            for (WinDef.HICON icon : largeIcons) {
                if (icon != null) {
                    try { User32.INSTANCE.DestroyIcon(icon); } catch (Exception ignored) {}
                }
            }
        }
        if (smallIcons != null) {
            for (WinDef.HICON icon : smallIcons) {
                if (icon != null) {
                    try { User32.INSTANCE.DestroyIcon(icon); } catch (Exception ignored) {}
                }
            }
        }
    }
}
