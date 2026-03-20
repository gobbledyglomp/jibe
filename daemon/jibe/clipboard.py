"""Clipboard synchronisation between Android and Linux.

This module will handle bidirectional clipboard sync:

  - When the Linux clipboard changes, send a `clipboard.sync` message
    to connected Android devices
  - When a `clipboard.sync` message arrives from Android, update the
    Linux clipboard

Why pyperclip?
  `pyperclip` provides a simple cross-desktop-environment clipboard API.
  It works with xclip, xsel, and wl-clipboard under the hood, so it
  supports both X11 and Wayland without us handling the differences.
  For more advanced clipboard features (images, rich text), we may
  eventually switch to direct D-Bus calls, but pyperclip covers the
  common text case cleanly.

For this milestone, this module is a stub. Clipboard sync will be
implemented in a future milestone.
"""

# pyperclip — cross-platform clipboard access
# Not imported yet to avoid requiring the dependency until we use it
# import pyperclip
