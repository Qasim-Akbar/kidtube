package com.bbro.kidtube.di

import android.content.Context
import androidx.room.Room
import com.bbro.kidtube.data.KidTubeDatabase
import com.bbro.kidtube.data.dao.ChildDao
import com.bbro.kidtube.data.dao.HistoryDao
import com.bbro.kidtube.data.dao.LocalEventDao
import com.bbro.kidtube.data.dao.OutboxDao
import com.bbro.kidtube.data.dao.PlanDao
import com.bbro.kidtube.data.dao.SnapshotDao
import com.bbro.kidtube.data.dao.VideoDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    // No fallbackToDestructiveMigration: schema changes need real migrations (user data = pending events/links).
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): KidTubeDatabase =
        Room.databaseBuilder(context, KidTubeDatabase::class.java, KidTubeDatabase.NAME).build()

    @Provides fun childDao(db: KidTubeDatabase): ChildDao = db.childDao()
    @Provides fun videoDao(db: KidTubeDatabase): VideoDao = db.videoDao()
    @Provides fun planDao(db: KidTubeDatabase): PlanDao = db.planDao()
    @Provides fun historyDao(db: KidTubeDatabase): HistoryDao = db.historyDao()
    @Provides fun localEventDao(db: KidTubeDatabase): LocalEventDao = db.localEventDao()
    @Provides fun outboxDao(db: KidTubeDatabase): OutboxDao = db.outboxDao()
    @Provides fun snapshotDao(db: KidTubeDatabase): SnapshotDao = db.snapshotDao()
}
