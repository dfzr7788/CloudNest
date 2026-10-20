# Use IShellLinkW explicitly: WScript.Shell may round-trip arguments through the ANSI code page.
if (-not ('CloudNestSyncShortcut' -as [type])) {
  Add-Type -TypeDefinition @"
using System;
using System.Text;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
public static class CloudNestSyncShortcut {
  [ComImport, Guid("00021401-0000-0000-C000-000000000046")] private class Link {}
  [ComImport, Guid("000214F9-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  private interface ILink {
    void GetPath([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder path, int count, IntPtr data, uint flags);
    void GetIDList(out IntPtr value);
    void SetIDList(IntPtr value);
    void GetDescription([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder text, int count);
    void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string text);
    void GetWorkingDirectory([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder text, int count);
    void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string text);
    void GetArguments([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder text, int count);
    void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string text);
    void GetHotkey(out short value);
    void SetHotkey(short value);
    void GetShowCmd(out int value);
    void SetShowCmd(int value);
    void GetIconLocation([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder path, int count, out int index);
    void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string path, int index);
    void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string path, uint reserved);
    void Resolve(IntPtr window, uint flags);
    void SetPath([MarshalAs(UnmanagedType.LPWStr)] string path);
  }
  public static void Create(string path, string target, string arguments, string directory) {
    object obj = new Link();
    try {
      ILink link = (ILink)obj;
      link.SetPath(target);
      link.SetArguments(arguments);
      link.SetWorkingDirectory(directory);
      link.SetDescription("CloudNest sync development preview; independent backups required.");
      ((IPersistFile)obj).Save(path, true);
    } finally { Marshal.FinalReleaseComObject(obj); }
  }
  public static string Arguments(string path) {
    object obj = new Link();
    try {
      ((IPersistFile)obj).Load(path, 0);
      var text = new StringBuilder(32768);
      ((ILink)obj).GetArguments(text, text.Capacity);
      return text.ToString();
    } finally { Marshal.FinalReleaseComObject(obj); }
  }
}
"@
}
