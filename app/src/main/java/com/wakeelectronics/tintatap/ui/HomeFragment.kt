package com.wakeelectronics.tintatap.ui

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.wakeelectronics.tintatap.R
import com.wakeelectronics.tintatap.data.ActionStore

class HomeFragment : Fragment(R.layout.fragment_home) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val store = ActionStore(requireContext())
        val rv = view.findViewById<RecyclerView>(R.id.rvActions)
        rv.layoutManager = GridLayoutManager(requireContext(), 2)

        val adapter = ActionAdapter(store.actions().toMutableList(), store.lastUsedId) { action ->
            findNavController().navigate(R.id.action_home_to_detail, bundleOf("actionId" to action.id))
        }
        rv.adapter = adapter

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END, 0
        ) {
            override fun isLongPressDragEnabled() = true
            override fun onMove(
                rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
            ): Boolean {
                adapter.move(vh.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                store.saveOrder(adapter.ids())
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(rv)
    }
}
