package com.voicetext.data

import android.content.Context

// Extension for easy access to database
val Context.database: AppDatabase
    get() = AppDatabase.getDatabase(this)
