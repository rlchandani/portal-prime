"""Output hygiene: nothing the suite prints or writes may carry a token, key or IP address."""

import re
import threading

IPV4 = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")
IPV6 = re.compile(r"\b(?:[0-9a-fA-F]{1,4}:){3,7}[0-9a-fA-F]{1,4}\b")
BEARER = re.compile(r"(?i)(bearer\s+)\S+")
SECRET_KEYS = {"token", "pin", "key", "secret", "password", "authorization", "ip", "host"}
MASK = "***"
IP_MASK = "x.x.x.x"


class Redactor:
    """Masks IPs, bearer values and every secret registered with [add]. Thread-safe."""

    def __init__(self):
        self._secrets = set()
        self._lock = threading.Lock()

    def add(self, secret):
        if secret and len(str(secret)) >= 4:
            with self._lock:
                self._secrets = self._secrets | {str(secret)}

    def text(self, value):
        out = str(value)
        with self._lock:
            secrets = sorted(self._secrets, key=len, reverse=True)
        for s in secrets:
            out = out.replace(s, MASK)
        out = BEARER.sub(r"\1" + MASK, out)
        out = IPV4.sub(IP_MASK, out)
        return IPV6.sub(IP_MASK, out)

    def data(self, value):
        """A redacted deep copy of JSON-like [value]; secret-named keys are dropped to a mask."""
        if isinstance(value, dict):
            return {k: (MASK if str(k).lower() in SECRET_KEYS else self.data(v)) for k, v in value.items()}
        if isinstance(value, (list, tuple)):
            return [self.data(v) for v in value]
        if isinstance(value, str):
            return self.text(value)
        return value
