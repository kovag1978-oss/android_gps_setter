package io.github.jqssun.gpssetter.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.room.Favorite

class FavListAdapter(
    ) : ListAdapter<Favorite,FavListAdapter.ViewHolder>(FavListComparetor()) {

    private var selectedFavoriteId: Long? = null
    var onItemClick : ((Favorite) -> Unit)? = null
    var onItemDelete : ((Favorite) -> Unit)? = null
    var onItemRename : ((Favorite) -> Unit)? = null

    fun selectFavorite(favorite: Favorite) {
        val previousPosition = currentList.indexOfFirst { it.id == selectedFavoriteId }
        selectedFavoriteId = favorite.id
        val selectedPosition = currentList.indexOfFirst { it.id == selectedFavoriteId }

        if (previousPosition >= 0) notifyItemChanged(previousPosition)
        if (selectedPosition >= 0 && selectedPosition != previousPosition) {
            notifyItemChanged(selectedPosition)
        }
    }

   inner class ViewHolder(view: View): RecyclerView.ViewHolder(view) {

        private val address: TextView = view.findViewById(R.id.address)
        private val delete: ImageView = itemView.findViewById(R.id.del)

        fun bind(favorite: Favorite){
            address.text = favorite.address
            itemView.isSelected = favorite.id == selectedFavoriteId
            delete.setOnClickListener {
                onItemDelete?.invoke(favorite)
            }
            address.setOnClickListener {
                onItemClick?.invoke(favorite)
            }
            address.setOnLongClickListener {
                onItemRename?.let {
                    it(favorite)
                    true
                } ?: false
            }
        }
    }

    class FavListComparetor : DiffUtil.ItemCallback<Favorite>() {
        override fun areItemsTheSame(oldItem: Favorite, newItem: Favorite): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Favorite, newItem: Favorite): Boolean {
            return oldItem == newItem
        }

    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.fav_items, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        if (item != null){
            holder.bind(item)

        }

    }

}