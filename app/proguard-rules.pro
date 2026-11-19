# WorkManager workers are created by class name.
-keep class app.riyaly.** extends androidx.work.ListenableWorker { *; }
-keep class app.riyaly.**Receiver { *; }
