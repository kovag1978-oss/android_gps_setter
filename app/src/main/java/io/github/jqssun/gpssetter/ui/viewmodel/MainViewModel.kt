package io.github.jqssun.gpssetter.ui.viewmodel


import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jqssun.gpssetter.repository.FavoriteRepository
import io.github.jqssun.gpssetter.room.Favorite
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.utils.ext.onIO
import io.github.jqssun.gpssetter.utils.ext.onMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val favoriteRepository: FavoriteRepository,
    private val prefManger: PrefManager
) : ViewModel() {

    val getLat  = prefManger.getLat
    val getLng  = prefManger.getLng
    val isStarted = prefManger.isStarted
    val mapType = prefManger.mapType


    private val _allFavList = MutableStateFlow<List<Favorite>>(emptyList())
    val allFavList : StateFlow<List<Favorite>> =  _allFavList
    fun doGetUserDetails(){
        onIO {
            favoriteRepository.getAllFavorites
                .catch { e ->
                    Timber.tag("Error in getting all save favorite").d(e.message.toString())
                }
                .collectLatest {
                    _allFavList.emit(it)
                }
        }
    }

    fun update(start: Boolean, la: Double, ln: Double)  {
        prefManger.update(start,la,ln)
    }

    private val _response = MutableLiveData<Long>()
    val response: LiveData<Long> = _response


    private fun insertNewFavorite(favorite: Favorite) = onIO {
        _response.postValue(favoriteRepository.addNewFavorite(favorite))

    }

    val isXposed = MutableLiveData<Boolean>(true)
    fun updateXposedState() {
        onMain {
            // isXposed.value = YukiHookAPI.Status.isModuleActive
            isXposed.value = false
        }
    }

    fun deleteFavorite(favorite: Favorite) = onIO {
        favoriteRepository.deleteFavorite(favorite)
    }

    fun updateFavorite(favorite: Favorite) = onIO {
        favoriteRepository.updateFavorite(favorite)
    }

    suspend fun getFavoritesSnapshot(): List<Favorite> =
        withContext(Dispatchers.IO) {
            favoriteRepository.getAllFavoritesSnapshot()
        }

    suspend fun importFavorites(favorites: List<Favorite>): Pair<Int, Int> =
        withContext(Dispatchers.IO) {
            favoriteRepository.importFavorites(favorites)
        }

    private fun getFavoriteSingle(i : Int) : Favorite {
        return favoriteRepository.getSingleFavorite(i.toLong())
    }


     fun storeFavorite(
        address: String,
        lat: Double,
        lon: Double
    ) = onIO {

            val slot: Int
            var i = 0
            while (true) {
                if(getFavoriteSingle(i) == null) {
                    slot = i
                    break
                } else {
                    i++
                }
        }
         insertNewFavorite(Favorite(id = slot.toLong(), address = address, lat = lat, lng = lon))
    }





}