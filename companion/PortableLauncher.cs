using System;
using System.Diagnostics;
using System.IO;
using System.Windows.Forms;

internal static class PortableLauncher
{
    [STAThread]
    private static int Main(string[] args)
    {
        bool selfTest = args.Length == 1 && args[0] == "--self-test";
        try
        {
            string folder = AppDomain.CurrentDomain.BaseDirectory;
            string python = Path.Combine(folder, "runtime", selfTest ? "python.exe" : "pythonw.exe");
            string app = Path.Combine(folder, "app.py");
            if (!File.Exists(python)) throw new FileNotFoundException("Paketli Python bulunamadı. Uygulamayı Setup.exe ile yeniden kurun.");
            if (!File.Exists(app)) throw new FileNotFoundException("Paketli app.py bulunamadı. Uygulamayı yeniden kurun.");
            var start = new ProcessStartInfo(python, "-I -B \"" + app + "\"" + (selfTest ? " --self-test" : ""))
            {
                WorkingDirectory = folder,
                UseShellExecute = false,
                CreateNoWindow = true
            };
            using (Process child = Process.Start(start))
            {
                if (selfTest) { child.WaitForExit(); return child.ExitCode; }
            }
            return 0;
        }
        catch (Exception error)
        {
            if (!selfTest) MessageBox.Show(error.Message, "Manhwa Takip", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }
}
