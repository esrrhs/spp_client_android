Imports System
Imports System.IO
Imports System.Text
Imports System.Reflection
Imports System.Xml

Public Class Main
    Public path As String = Application.StartupPath()

    Sub terminate()
        If System.Diagnostics.Process.GetProcessesByName("frps").Length > 0 Then
            Try
                Dim i As Integer
                Dim proc As Process()
                proc = Process.GetProcessesByName("frps")
                For i = 0 To proc.Length - 1
                    proc(i).Kill()
                Next
                Switch.Text = "Start"
            Catch ex As Exception

            End Try
        End If
        Application.Exit()
    End Sub

    Private WithEvents MyProcess As Process

    Private Delegate Sub AppendOutputTextDelegate(ByVal text As String)

    Private Sub Form1_Load(sender As Object, e As EventArgs) Handles MyBase.Load
        'Loading code
        If My.Computer.FileSystem.FileExists(path & "/frps.exe") Then
        Else
            MsgBox("need frps.exe", MsgBoxStyle.OkOnly, "frp GUI")
            Application.Exit()
            Return
        End If
        If My.Computer.FileSystem.FileExists(path & "/frps.ini") Then
        Else
            MsgBox("need frps.ini", MsgBoxStyle.OkOnly, "frp GUI")
            Application.Exit()
            Return
        End If

        My.Computer.Registry.CurrentUser.OpenSubKey("SOFTWARE\Microsoft\Windows\CurrentVersion\Run", True).SetValue(Application.ProductName, Application.ExecutablePath & " -s")

        If System.Diagnostics.Process.GetProcessesByName("frps").Length > 0 Then
            Try
                Dim i As Integer
                Dim proc As Process()
                proc = Process.GetProcessesByName("frps")
                For i = 0 To proc.Length - 1
                    proc(i).Kill()
                Next
                Switch.Text = "Start"
            Catch ex As Exception

            End Try
        End If

        Go()

    End Sub

    Protected Overrides Sub OnClosing(ByVal e As System.ComponentModel.CancelEventArgs)
        Me.Hide()
        NotifyIcon1.BalloonTipTitle = "Notice"
        NotifyIcon1.BalloonTipText = "frps GUI is running in the background"
        NotifyIcon1.ShowBalloonTip(2)
        e.Cancel = True
    End Sub

    Private Sub Label1_Click(sender As Object, e As EventArgs)

    End Sub

    Private Sub ExportConfigToolStripMenuItem_Click(sender As Object, e As EventArgs)

    End Sub

    Private Sub NotifyIcon1_MouseDoubleClick(sender As Object, e As MouseEventArgs) Handles NotifyIcon1.MouseDoubleClick
        Me.Show()
        Me.Opacity = 100%
        Me.Activate()
    End Sub

    Private Sub Switch_Click(sender As Object, e As EventArgs) Handles Switch.Click
        Go()
    End Sub

    Private Sub Go()
        If System.Diagnostics.Process.GetProcessesByName("frps").Length > 0 Then
            Try
                Dim i As Integer
                Dim proc As Process()
                proc = Process.GetProcessesByName("frps")
                For i = 0 To proc.Length - 1
                    proc(i).Kill()
                Next
                Switch.Text = "Start"
            Catch ex As Exception

            End Try
        Else
            MyProcess = New Process
            With MyProcess.StartInfo
                .FileName = "frps.exe"
                .Arguments = "-c """ & path & "\frps.ini"""
                .UseShellExecute = False
                .CreateNoWindow = True
                .RedirectStandardInput = True
                .RedirectStandardOutput = True
                .RedirectStandardError = True
            End With
            Control.CheckForIllegalCrossThreadCalls = False
            MyProcess.Start()
            Switch.Text = "Stop"
        End If
    End Sub

    Private Sub OpenFrpciniToolStripMenuItem_Click(sender As Object, e As EventArgs)
        Try
            System.Diagnostics.Process.Start(path & "/frpc.ini")
        Catch ex As Exception
            MsgBox("Unable to open frpc.ini. Maybe it was deleted?")
        End Try
    End Sub

    Private Sub SToolStripMenuItem1_Click(sender As Object, e As EventArgs) Handles SToolStripMenuItem1.Click
        terminate()
    End Sub

    Private Sub ContextMenuStrip1_Opening(sender As Object, e As ComponentModel.CancelEventArgs) Handles ContextMenuStrip1.Opening

    End Sub
End Class
