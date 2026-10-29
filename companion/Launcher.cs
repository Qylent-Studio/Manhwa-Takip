using System;
using System.Diagnostics;
using System.IO;
using System.Windows.Forms;

internal static class Launcher
{
    private const string Pythonw = @"__PYTHONW_PATH__";
    private const string ApplicationFolder = @"__APPLICATION_FOLDER__";

    [STAThread]
    private static int Main(string[] args)
    {
        try
        {
            string folder = ApplicationFolder;
            string app = Path.Combine(folder, "app.py");
            if (!File.Exists(Pythonw)) throw new FileNotFoundException("Kurulu Python çalıştırıcısı bulunamadı.");
            if (!File.Exists(app)) throw new FileNotFoundException("Kurulu app.py bulunamadı.");
            bool selfTest = args.Length == 1 && args[0] == "--self-test";
            var start = new ProcessStartInfo(Pythonw, "\"" + app + "\"" + (selfTest ? " --self-test" : ""))
            {
                WorkingDirectory = folder,
                UseShellExecute = false,
                CreateNoWindow = true
            };
            start.EnvironmentVariables["LOCALAPPDATA"] = Directory.GetParent(folder).FullName;
            using (Process child = Process.Start(start))
            {
                if (selfTest) { child.WaitForExit(); return child.ExitCode; }
            }
            return 0;
        }
        catch (Exception error)
        {
            var native = error as System.ComponentModel.Win32Exception;
            string detail = error.GetType().Name + ": " + error.Message + (native == null ? "" : " (Windows " + native.NativeErrorCode + ")");
            try { File.WriteAllText(Path.Combine(ApplicationFolder, "launcher-error.txt"), detail); } catch { }
            if (args.Length == 1 && args[0] == "--self-test") return 1;
            MessageBox.Show("Uygulama başlatılamadı.\n\n" + detail,
                            "Manhwa Takip", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }
}
