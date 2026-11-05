package com.bbro.kidtube.di

import android.content.Context
import androidx.room.Room
import com.bbro.kidtube.data.KidTubeDatabase
import com.bbro.kidtube.data.OutboxDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): KidTubeDatabase =
        Room.databaseBuilder(context, KidTubeDatabase::class.java, KidTubeDatabase.NAME).build()

    @Provides
    fun outboxDao(db: KidTubeDatabase): OutboxDao = db.outboxDao()
}
