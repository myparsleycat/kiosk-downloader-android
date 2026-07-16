package com.kiodl.android.di

import android.content.Context
import androidx.room.Room
import com.kiodl.android.data.local.DownloadDao
import com.kiodl.android.data.local.KioskDatabase
import com.kiodl.android.data.local.UploadDao
import com.kiodl.android.data.remote.KioApiClient
import com.kiodl.android.data.remote.TransferItApiClient
import com.kiodl.android.data.remote.KioUploadClient
import com.kiodl.android.data.repository.DefaultCollectionSourceRepository
import com.kiodl.android.data.repository.RoomDownloadRepository
import com.kiodl.android.domain.repository.CollectionSourceRepository
import com.kiodl.android.domain.repository.DownloadRepository
import com.kiodl.android.domain.repository.UploadRepository
import com.kiodl.android.data.repository.RoomUploadRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindings {
    @Binds
    @Singleton
    abstract fun bindDownloadRepository(repository: RoomDownloadRepository): DownloadRepository

    @Binds
    @Singleton
    abstract fun bindCollectionSourceRepository(
        repository: DefaultCollectionSourceRepository,
    ): CollectionSourceRepository

    @Binds
    @Singleton
    abstract fun bindUploadRepository(repository: RoomUploadRepository): UploadRepository
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KioskDatabase =
        Room.databaseBuilder(context, KioskDatabase::class.java, "kiosk-downloader.db")
            .addMigrations(
                KioskDatabase.MIGRATION_1_2,
                KioskDatabase.MIGRATION_2_3,
                KioskDatabase.MIGRATION_3_4,
                KioskDatabase.MIGRATION_4_5,
                KioskDatabase.MIGRATION_5_6,
                KioskDatabase.MIGRATION_6_7,
                KioskDatabase.MIGRATION_7_8,
                KioskDatabase.MIGRATION_8_9,
                KioskDatabase.MIGRATION_9_10,
            )
            .build()

    @Provides
    fun provideDownloadDao(database: KioskDatabase): DownloadDao = database.downloadDao()

    @Provides
    fun provideUploadDao(database: KioskDatabase): UploadDao = database.uploadDao()

    @Provides
    @Singleton
    fun provideHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(100, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36",
                    )
                    .build(),
            )
        }
        .build()

    @Provides
    @Singleton
    fun provideKioApiClient(httpClient: OkHttpClient) = KioApiClient(httpClient)

    @Provides
    @Singleton
    fun provideTransferItApiClient(httpClient: OkHttpClient) = TransferItApiClient(httpClient)

    @Provides
    @Singleton
    fun provideKioUploadClient(httpClient: OkHttpClient) = KioUploadClient(httpClient)
}
