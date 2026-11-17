Hier liegt die CA, der die App zusätzlich zu den System-Zertifikaten vertraut.

  cp /pfad/zu/deiner-lan-ca.crt assets/ca.crt

Der Build funktioniert auch ohne diese Datei: dann greift die App nur auf
System-CAs zu (echte Zertifikate) oder auf "Beliebiges Zertifikat akzeptieren"
in den Einstellungen - praktisch zum Ausprobieren, unschön im Ernstbetrieb.

Die Datei ist in .gitignore, weil jede Heimnetz-Installation ihre eigene CA hat.
