package com.github.komi2233.aozorareader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(books: List<BookEntity>)

    @Query("SELECT * FROM books WHERE title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%' ORDER BY CASE WHEN lastOpenedAt > 0 THEN 0 ELSE 1 END, lastOpenedAt DESC, title ASC")
    fun searchBooks(query: String): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE isDownloaded = 1 ORDER BY lastOpenedAt DESC")
    fun getDownloadedBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE personId = :personId AND bookId = :bookId LIMIT 1")
    suspend fun getBook(personId: String, bookId: String): BookEntity?

    @Update
    suspend fun updateBook(book: BookEntity)

    @Query("SELECT COUNT(*) FROM books")
    suspend fun getCount(): Int
}
