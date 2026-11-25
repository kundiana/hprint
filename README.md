# hPrint

An unofficial, free Android app that prints PDFs and images to older HP printers (like the LaserJet 1300 and P1102 series) over a USB OTG cable.

No ads. No page limits. No accounts. No cloud. Just plug it in and print.

*Disclaimer: This is an unofficial, community-built app. It is not affiliated with, endorsed by, or supported by HP.*

## The Story

HP dropped official driver support for these older "winprinters" (host-based PCL printers) on modern operating systems. If you have one of these reliable workhorses lying around, you were out of luck... until now.

hPrint talks directly to the printer's USB interface, converts your document to a 300 DPI raster image, and sends it using the PCL protocol the printer understands.

## Supported Printers

- ✅ **HP LaserJet 1302** (Tested and verified by the developer)
- ❓ **HP LaserJet P1102** (Uses the same protocol, untested)
- ❓ Other older HP PCL-compatible printers (Untested)

If you test a printer, please open a GitHub Issue with your printer model and the result!

## How to Use

1. Connect your printer to your phone using a USB OTG cable.
2. Turn the printer on.
3. Open hPrint. (You can also "Share" a PDF or image from any app directly to hPrint.)
4. Allow the USB permission prompt if it appears.
5. Select your file, choose your paper size and alignment, and tap **Print**.

*Note: Laser printers take 10–15 seconds to warm up before the first page prints. This is normal. Wait for the "Done." message in the app log.*

## Features

- Print standard PDFs (text-based).
- Print photos and screenshots (uses an adaptive threshold algorithm to keep text crisp and readable).
- Choose your paper size (A4, A5, Letter, Legal).
- Align images (Top-Left, Center, Bottom-Right, etc.).
- Treat scanned PDFs as images for better contrast.
- Built-in print log — if something goes wrong, tap **Share Log** to export a `.txt` file and attach it to a GitHub Issue.

## How to Get the App (No Android Studio Needed)

GitHub builds the APK for you in the cloud.

1. Go to the **Releases** tab on the right side of this page.
2. Download the latest `hprint.apk` file.
3. Install it on your phone (allow "install unknown apps" when asked).

## How to Build It Yourself

If you want to fork the project and make changes:

1. Fork this repository.
2. Make your changes (the main UI is in `MainActivity.kt`, the printer protocol is in `PclDriver.kt`).
3. Go to the **Actions** tab. GitHub will automatically build a new APK for you.
4. Download the `hprint-apk` artifact to test your changes.

## Contributing

Found a bug? Have a printer that works? Open an issue! Pull requests are welcome.

If you want to add support for a new printer, you only need to write a new driver class and add it to the registry. The built-in log export makes it easy to share diagnostic info when reporting issues.

## License

This project is licensed under the GNU General Public License v2.0 or later (GPL-2.0-or-later). See the `LICENSE` file for details.
