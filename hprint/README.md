# hprint, step 1: does the phone talk to the printer?

This is a tiny test app. It finds your HP P1102 over the OTG cable and sends
it **one ready-made test page**. If a page comes out, the hardest part works
and we build the rest (printing your real PDFs and photos) on top of it.

Nothing here costs money. No ads, no page limits.

## Get the app onto your phone (free, no Android Studio)

GitHub builds the APK for you in the cloud.

1. Make a free account at github.com.
2. Create a new **public** repository (any name).
3. Put every file from this folder in it, keeping the same folders.
   - On a computer: "Add file" -> "Upload files", drag the whole folder in.
   - On a phone browser: switch Chrome to "Desktop site", then use
     "Add file" -> "Create new file" for each file. In the name box, typing
     `app/src/main/java/app/freeprint/MainActivity.kt` creates the folders.
     `app/src/main/assets/testpage.zjs` is a binary file, so it needs the
     "Upload files" button instead.
   - The file `.github/workflows/build.yml` must be in exactly that place.
4. Open the **Actions** tab, then "Build APK". It takes about 5 minutes.
   Open the finished run and download **hprint-apk**. Unzip it.
5. Install the `.apk` on your phone (allow "install unknown apps" when asked).

If the build shows a red error, tap it, copy the red text, and send it to me.
I wrote this without being able to compile it here, so a first-build error is
possible and normal.

## Test it

1. Plug the printer into the phone with the OTG cable. Switch the printer on.
   Put paper in.
2. Open **hprint**, tap **1. Find printer**.
3. Tap **2. Print test page** and press OK on the Android pop-up.
4. A test page (text and a circle inside a square) should print.

Whatever happens, take a screenshot of the text on screen and send it to me.
It lists exactly how your printer shows up over USB, which is what I need for
the next step.
