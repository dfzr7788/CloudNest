"""Current Windows user's non-roaming Generic Credential; never a plaintext token file.

Layout: https://learn.microsoft.com/windows/win32/api/wincred/ns-wincred-credentialw
"""
import ctypes
from ctypes import wintypes
import os


class Credential(ctypes.Structure):
    _fields_ = [
        ("Flags", wintypes.DWORD), ("Type", wintypes.DWORD),
        ("TargetName", wintypes.LPWSTR), ("Comment", wintypes.LPWSTR),
        ("LastWritten", wintypes.FILETIME), ("CredentialBlobSize", wintypes.DWORD),
        ("CredentialBlob", ctypes.POINTER(ctypes.c_ubyte)), ("Persist", wintypes.DWORD),
        ("AttributeCount", wintypes.DWORD), ("Attributes", ctypes.c_void_p),
        ("TargetAlias", wintypes.LPWSTR), ("UserName", wintypes.LPWSTR),
    ]


class WindowsCredentials:
    def __init__(self):
        if os.name != "nt":
            raise RuntimeError("Device credentials require Windows Credential Manager")
        self.api = ctypes.WinDLL("Advapi32.dll", use_last_error=True)
        self.api.CredWriteW.argtypes = [ctypes.POINTER(Credential), wintypes.DWORD]
        self.api.CredWriteW.restype = wintypes.BOOL
        self.api.CredReadW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD,
                                     ctypes.POINTER(ctypes.POINTER(Credential))]
        self.api.CredReadW.restype = wintypes.BOOL
        self.api.CredDeleteW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD]
        self.api.CredDeleteW.restype = wintypes.BOOL
        self.api.CredFree.argtypes = [ctypes.c_void_p]
        self.api.CredFree.restype = None

    def write(self, target, token):
        data = token.encode("utf-8")
        if len(data) > 256:
            raise ValueError("Invalid credential length")
        blob = (ctypes.c_ubyte * len(data)).from_buffer_copy(data)
        value = Credential(Type=1, TargetName=target, UserName="CloudNestSync",
                           CredentialBlobSize=len(data), CredentialBlob=blob, Persist=2)
        if not self.api.CredWriteW(ctypes.byref(value), 0):
            raise ctypes.WinError(ctypes.get_last_error())

    def read(self, target):
        value = ctypes.POINTER(Credential)()
        if not self.api.CredReadW(target, 1, 0, ctypes.byref(value)):
            raise RuntimeError("Device credential unavailable; link this folder again")
        try:
            return ctypes.string_at(value.contents.CredentialBlob,
                                    value.contents.CredentialBlobSize).decode("utf-8")
        finally:
            self.api.CredFree(value)

    def delete(self, target):
        if not self.api.CredDeleteW(target, 1, 0) and ctypes.get_last_error() != 1168:
            raise ctypes.WinError(ctypes.get_last_error())
