/**
 * Dashboard/login locale strings (default English, Spanish optional).
 * Theme: localStorage jibe_theme = dark | light
 */
(function () {
  const LANG_KEY = 'jibe_lang';
  const THEME_KEY = 'jibe_theme';

  const STRINGS = {
    en: {
      meta: {
        docTitle: 'Jibe — Dashboard',
      },
      login: {
        pageTitle: 'Jibe — Login',
        title: 'Jibe',
        subtitle: 'Dashboard login (localhost only)',
        username: 'Username',
        password: 'Password',
        signIn: 'Sign in',
        forgotLink: 'Forgot password?',
        recoveryBanner:
          'Recovery is enabled. If you are locked out, open this file and use the token inside to reset your password:\n{path}',
        recoveryModalTitle: 'Reset password with recovery token',
        recoveryToken: 'Recovery token',
        newPassword: 'New password (min 10 characters)',
        resetSubmit: 'Set new password',
        close: 'Close',
        recoveryDisabledHint:
          'Recovery file not present on this machine. Use your saved password or restore from backup.',
        noRecoveryFile:
          'No recovery token file is configured. Save your password securely; an admin can rotate a recovery token from Settings after login.',
      },
      nav: {
        devices: 'Devices',
        history: 'History',
        stats: 'Statistics',
        daemon: 'Daemon',
        settings: 'Settings',
      },
      common: {
        logout: 'Logout',
        apply: 'Apply',
        online: 'online',
        offline: 'offline',
        revoke: 'Revoke',
        prev: 'Prev',
        next: 'Next',
        page: 'Page',
      },
      devices: {
        title: 'Devices',
        name: 'Name',
        lastSeen: 'Last seen',
        paired: 'Paired',
        revokeConfirm: 'Revoke pairing for',
        revokeFail: 'Revoke failed:',
        renamePrompt: 'New device name',
        renameTitle: 'Click to rename',
      },
      history: {
        title: 'History',
        transfers: 'Transfers',
        clipboard: 'Clipboard',
        notifications: 'Notifications',
        deviceFilter: 'Device / source id',
        fromIso: 'From (ISO)',
        toIso: 'To (ISO)',
        status: 'Status',
        direction: 'Direction',
        appContains: 'App contains',
        any: 'any',
        file: 'File',
        size: 'Size',
        statusCol: 'Status',
        started: 'Started',
        source: 'Source',
        dir: 'Dir',
        preview: 'Preview',
        when: 'When',
        app: 'App',
        titleCol: 'Title',
        received: 'Received',
      },
      stats: {
        title: 'Statistics',
        transfersCompleted: 'Transfers completed',
        bytesTransferred: 'Bytes transferred',
        clipboardEvents: 'Clipboard events',
        notifications: 'Notifications',
        topDevice: 'Most active device',
        activity7d: 'Activity (7 days)',
        noData: 'No data yet',
        events: 'events',
        legendTransfers: 'transfers',
        legendClipboard: 'clipboard',
      },
      daemon: {
        title: 'Daemon',
        version: 'Version',
        uptime: 'Uptime',
        connected: 'Connected devices',
        pairingActive: 'Pairing active',
        tls: 'TLS',
        pairing: 'Pairing',
        startPairing: 'Start pairing',
        stopPairing: 'Stop pairing',
        expiresIn: 'Expires in {n}s · failed attempts (session): {f}',
        inactive: 'Inactive · failed attempts (session): {f}',
        tlsSection: 'TLS',
        regenCert: 'Regenerate certificate',
        regenConfirm: 'Delete TLS certs and generate new ones? Restart daemon to load them.',
        eventLogTitle: 'Event log',
        eventLogHint:
          'Live tail of notable daemon activity (devices, pairing, transfers, pings, dashboard actions). Localhost only.',
        sendPing: 'Send ping to connected devices',
        noEvents: 'No events recorded yet.',
      },
      settings: {
        title: 'Settings',
        appearance: 'Appearance',
        theme: 'Theme',
        themeDark: 'Dark',
        themeLight: 'Light',
        language: 'Language',
        langEn: 'English',
        langEs: 'Spanish',
        account: 'Account',
        currentPassword: 'Current password',
        newPassword: 'New password',
        changePassword: 'Change password',
        passwordChanged: 'Password updated.',
        passwordFail: 'Could not change password.',
        data: 'Data',
        clearHistory: 'Clear activity history',
        clearHistoryHelp:
          'Removes transfer, clipboard, and notification logs. Statistics totals reset.',
        clearHistoryConfirm:
          'Delete all activity history (transfers, clipboard, notifications)? This cannot be undone.',
        clearStats: 'Clear session statistics',
        clearStatsHelp:
          'Removes connection session rows from the database. History lists are unchanged.',
        clearStatsConfirm: 'Delete all stored session records?',
        advanced: 'Advanced',
        devEventLog: 'Show daemon event log on Daemon page',
        devEventLogHelp:
          'Streams notable events to the browser and still lets you broadcast an application ping for RTT.',
        recoveryRotate: 'Generate new recovery token',
        recoveryRotateHelp:
          'Invalidates the old token file. Copy the new token immediately — it is shown only once.',
        recoveryRotateConfirm: 'Generate a new recovery token?',
        recoveryNewToken: 'New recovery token (save securely):',
        adminOnly: 'Administrator only.',
      },
      time: {
        secAgo: '{n}s ago',
        minAgo: '{n}m ago',
        hourAgo: '{n}h ago',
      },
    },
    es: {
      meta: {
        docTitle: 'Jibe — Panel',
      },
      login: {
        pageTitle: 'Jibe — Acceso',
        title: 'Jibe',
        subtitle: 'Acceso al panel (solo localhost)',
        username: 'Usuario',
        password: 'Contraseña',
        signIn: 'Entrar',
        forgotLink: '¿Olvidaste la contraseña?',
        recoveryBanner:
          'Recuperación activa. Si no puedes entrar, abre este archivo y usa el token que contiene para restablecer la contraseña:\n{path}',
        recoveryModalTitle: 'Restablecer contraseña con token de recuperación',
        recoveryToken: 'Token de recuperación',
        newPassword: 'Nueva contraseña (mín. 10 caracteres)',
        resetSubmit: 'Establecer nueva contraseña',
        close: 'Cerrar',
        recoveryDisabledHint:
          'No hay archivo de recuperación en este equipo. Usa la contraseña guardada o restaura desde una copia.',
        noRecoveryFile:
          'No hay token de recuperación. Guarda tu contraseña; un administrador puede generar un token en Ajustes tras entrar.',
      },
      nav: {
        devices: 'Dispositivos',
        history: 'Historial',
        stats: 'Estadísticas',
        daemon: 'Demonio',
        settings: 'Ajustes',
      },
      common: {
        logout: 'Salir',
        apply: 'Aplicar',
        online: 'en línea',
        offline: 'desconectado',
        revoke: 'Revocar',
        prev: 'Ant.',
        next: 'Sig.',
        page: 'Página',
      },
      devices: {
        title: 'Dispositivos',
        name: 'Nombre',
        lastSeen: 'Última vez',
        paired: 'Emparejado',
        revokeConfirm: '¿Revocar emparejamiento de',
        revokeFail: 'No se pudo revocar:',
        renamePrompt: 'Nuevo nombre del dispositivo',
        renameTitle: 'Clic para renombrar',
      },
      history: {
        title: 'Historial',
        transfers: 'Transferencias',
        clipboard: 'Portapapeles',
        notifications: 'Notificaciones',
        deviceFilter: 'ID dispositivo / origen',
        fromIso: 'Desde (ISO)',
        toIso: 'Hasta (ISO)',
        status: 'Estado',
        direction: 'Dirección',
        appContains: 'App contiene',
        any: 'cualquiera',
        file: 'Archivo',
        size: 'Tamaño',
        statusCol: 'Estado',
        started: 'Inicio',
        source: 'Origen',
        dir: 'Dir.',
        preview: 'Vista previa',
        when: 'Cuándo',
        app: 'App',
        titleCol: 'Título',
        received: 'Recibido',
      },
      stats: {
        title: 'Estadísticas',
        transfersCompleted: 'Transferencias completadas',
        bytesTransferred: 'Bytes transferidos',
        clipboardEvents: 'Eventos de portapapeles',
        notifications: 'Notificaciones',
        topDevice: 'Dispositivo más activo',
        activity7d: 'Actividad (7 días)',
        noData: 'Sin datos aún',
        events: 'eventos',
        legendTransfers: 'transferencias',
        legendClipboard: 'portapapeles',
      },
      daemon: {
        title: 'Demonio',
        version: 'Versión',
        uptime: 'Tiempo activo',
        connected: 'Dispositivos conectados',
        pairingActive: 'Emparejamiento activo',
        tls: 'TLS',
        pairing: 'Emparejamiento',
        startPairing: 'Iniciar emparejamiento',
        stopPairing: 'Detener emparejamiento',
        expiresIn: 'Caduca en {n}s · intentos fallidos (sesión): {f}',
        inactive: 'Inactivo · intentos fallidos (sesión): {f}',
        tlsSection: 'TLS',
        regenCert: 'Regenerar certificado',
        regenConfirm:
          '¿Eliminar certificados TLS y generar otros nuevos? Reinicia el demonio para cargarlos.',
        eventLogTitle: 'Registro de eventos',
        eventLogHint:
          'Actividad destacada del demonio en vivo (dispositivos, emparejamiento, transferencias, pings, acciones del panel). Solo localhost.',
        sendPing: 'Enviar ping a dispositivos conectados',
        noEvents: 'Aún no hay eventos registrados.',
      },
      settings: {
        title: 'Ajustes',
        appearance: 'Apariencia',
        theme: 'Tema',
        themeDark: 'Oscuro',
        themeLight: 'Claro',
        language: 'Idioma',
        langEn: 'Inglés',
        langEs: 'Español',
        account: 'Cuenta',
        currentPassword: 'Contraseña actual',
        newPassword: 'Nueva contraseña',
        changePassword: 'Cambiar contraseña',
        passwordChanged: 'Contraseña actualizada.',
        passwordFail: 'No se pudo cambiar la contraseña.',
        data: 'Datos',
        clearHistory: 'Borrar historial de actividad',
        clearHistoryHelp:
          'Elimina registros de transferencias, portapapeles y notificaciones. Las estadísticas vuelven a cero.',
        clearHistoryConfirm:
          '¿Eliminar todo el historial de actividad? Esta acción no se puede deshacer.',
        clearStats: 'Borrar estadísticas de sesiones',
        clearStatsHelp:
          'Elimina las filas de sesiones de conexión en la base de datos. Las listas del historial no cambian.',
        clearStatsConfirm: '¿Eliminar todos los registros de sesión?',
        advanced: 'Avanzado',
        devEventLog: 'Mostrar registro de eventos del demonio en la página Demonio',
        devEventLogHelp:
          'Transmite eventos destacados al navegador y sigue permitiendo un ping de aplicación para medir RTT.',
        recoveryRotate: 'Generar nuevo token de recuperación',
        recoveryRotateHelp:
          'Invalida el token anterior. Copia el nuevo al instante — solo se muestra una vez.',
        recoveryRotateConfirm: '¿Generar un nuevo token de recuperación?',
        recoveryNewToken: 'Nuevo token de recuperación (guárdalo en lugar seguro):',
        adminOnly: 'Solo administrador.',
      },
      time: {
        secAgo: 'hace {n}s',
        minAgo: 'hace {n} min',
        hourAgo: 'hace {n} h',
      },
    },
  };

  function _get(obj, path) {
    return path.split('.').reduce(function (a, k) {
      return a && a[k] !== undefined ? a[k] : null;
    }, obj);
  }

  window.JibeI18n = {
    STRINGS: STRINGS,
    lang() {
      const v = localStorage.getItem(LANG_KEY);
      return v === 'es' ? 'es' : 'en';
    },
    setLang(code) {
      localStorage.setItem(LANG_KEY, code === 'es' ? 'es' : 'en');
      document.documentElement.lang = code === 'es' ? 'es' : 'en';
      window.dispatchEvent(new CustomEvent('jibe-locale'));
    },
    theme() {
      const v = localStorage.getItem(THEME_KEY);
      return v === 'light' ? 'light' : 'dark';
    },
    setTheme(mode) {
      const m = mode === 'light' ? 'light' : 'dark';
      localStorage.setItem(THEME_KEY, m);
      document.documentElement.dataset.theme = m;
      window.dispatchEvent(new CustomEvent('jibe-theme'));
    },
    initDocument() {
      document.documentElement.dataset.theme = this.theme();
      document.documentElement.lang = this.lang() === 'es' ? 'es' : 'en';
    },
    t(key) {
      const lang = this.lang();
      const picked = _get(STRINGS[lang], key);
      if (picked != null) return picked;
      const fallback = _get(STRINGS.en, key);
      return fallback != null ? fallback : key;
    },
  };

  window.JibeI18n.initDocument();
})();
