"""Android notification mirroring to the Linux desktop.

This module will handle incoming `notification` messages from Android
and display them as native Linux desktop notifications using D-Bus
(the `org.freedesktop.Notifications` interface).

Why D-Bus?
  D-Bus is the standard IPC (inter-process communication) system on
  Linux desktops. The notification spec (`org.freedesktop.Notifications`)
  is implemented by all major desktop environments (GNOME, KDE, XFCE,
  Sway/wlroots) and standalone notification daemons (dunst, mako).
  Using D-Bus directly means we don't depend on any specific DE — the
  notifications will work everywhere.

Why dbus-python?
  `dbus-python` is the reference Python binding for D-Bus. It's lower-
  level than alternatives like `dasbus` or `pydbus`, but it's the most
  stable and widely packaged. Note: it requires system packages
  (`pkgconf`, `dbus` headers) to build, so it's deferred from
  requirements.txt until this module is actually implemented.

For this milestone, this module is a stub. Notification mirroring will
be implemented in a future milestone.
"""

# dbus-python — Linux D-Bus bindings
# Not imported yet: requires system deps and is deferred to a later milestone
# import dbus
