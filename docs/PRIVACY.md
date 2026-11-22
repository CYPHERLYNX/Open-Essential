# Privacy

Open Essential processes capture text, images and speech with on-device models. It does not require an AI account or API key.

## Local storage and analysis

Captures, recordings, notes, AI results, tasks, collections and search data are kept in the app's private storage. Model files are downloaded or imported into app storage. Text, vision and speech inference run locally through llama.cpp / libmtmd and whisper.cpp.

The app disables Android cloud backup and device transfer in its manifest and backup rules. There is no built-in cloud copy of your capture library. Deleted captures remain in Trash for 30 days before removal; you can restore or remove them there.

## Network activity

- **Model downloads:** when you choose Download, the app requests the configured model files from Hugging Face. Those servers and their delivery providers receive ordinary download-request information, including your IP address.
- **Link previews:** the first shared link prompts you to choose whether the app may fetch public previews. You can change that choice in Settings. When enabled, requests go to the linked website and may go to its media hosts or public preview providers. The shared URL is included where a preview provider needs it. These requests can reveal that you requested the URL, including any information already contained in that URL.
- **Sharing and export:** when you choose to share, export or open something outside the app, the destination you select handles that content under its own terms.

Web previews are distinct from AI inference. Turning previews off avoids those preview requests; it does not cancel a model download you started. Captures are not uploaded to an online AI service by the current inference pipeline. Do not interpret local AI as a promise that the app never makes any network request.

## Optional capture access

The accessibility shortcut is optional. It handles volume-down presses, can record the active app's name as capture context, and requests a screenshot when you trigger capture. The assistant shortcut uses Android's assistant integration. Screen captures may contain sensitive information, so review what is visible before capturing or sharing.

Recording uses the microphone with a visible foreground notification. Model downloads also use a foreground notification. Other permissions support notifications, reminders, restoring reminder schedules after restart, haptics and optional photo-location metadata.

## Your controls

You can disable automatic analysis or web previews, remove downloaded models, delete captures, clear app data or uninstall. Export anything you want to keep before clearing app data or uninstalling. App-private storage is not a substitute for your phone's screen lock and device security.
