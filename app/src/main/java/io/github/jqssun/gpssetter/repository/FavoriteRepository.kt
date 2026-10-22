package io.github.jqssun.gpssetter.repository


import androidx.annotation.WorkerThread
import io.github.jqssun.gpssetter.room.Favorite
import io.github.jqssun.gpssetter.room.FavoriteDao
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class FavoriteRepository @Inject constructor(private val favoriteDao: FavoriteDao) {

        val getAllFavorites: Flow<List<Favorite>>
        get() =  favoriteDao.getAllFavorites()

        suspend fun getAllFavoritesSnapshot(): List<Favorite> =
                favoriteDao.getAllFavoritesSnapshot()

        suspend fun importFavorites(favorites: List<Favorite>): Pair<Int, Int> {
                val favoritesById = favoriteDao.getAllFavoritesSnapshot()
                        .associateBy { it.id ?: error("Favorite is missing its database ID") }
                        .toMutableMap()
                val additions = mutableListOf<Favorite>()
                var duplicates = 0
                var nextAvailableId = 0L

                favorites.forEach { favorite ->
                        val id = favorite.id ?: error("Imported favorite is missing its ID")
                        val existing = favoritesById[id]
                        if (existing != null && existing.hasSameLocation(favorite)) {
                                duplicates++
                        } else {
                                val availableId = if (existing == null) {
                                        id
                                } else {
                                        while (favoritesById.containsKey(nextAvailableId)) {
                                                nextAvailableId++
                                        }
                                        nextAvailableId++
                                }
                                val addition = favorite.copy(id = availableId)
                                favoritesById[availableId] = addition
                                additions += addition
                        }
                }

                val inserted = favoriteDao.insertFavorites(additions).count { it != -1L }
                return inserted to (duplicates + additions.size - inserted)
        }

        private fun Favorite.hasSameLocation(other: Favorite): Boolean =
                address == other.address && lat == other.lat && lng == other.lng

        @Suppress("RedundantSuspendModifier")
        @WorkerThread
        suspend fun addNewFavorite(favorite: Favorite) : Long {
            return favoriteDao.insertToRoomDatabase(favorite)
        }

        suspend fun deleteFavorite(favorite: Favorite) {
          favoriteDao.deleteSingleFavorite(favorite)
       }


       fun getSingleFavorite(id: Long) : Favorite {
       return favoriteDao.getSingleFavorite(id)

    }

}