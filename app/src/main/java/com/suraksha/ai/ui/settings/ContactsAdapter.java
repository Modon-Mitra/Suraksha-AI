package com.suraksha.ai.ui.settings;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.suraksha.ai.databinding.ItemContactBinding;
import com.suraksha.ai.model.EmergencyContact;

import java.util.List;

public class ContactsAdapter
        extends RecyclerView.Adapter<ContactsAdapter.VH> {

    public interface OnRemove {

        void onRemove(int pos);
    }

    public interface OnClick {

        void onClick(int pos);
    }

    private final List<EmergencyContact> list;

    private final OnRemove onRemove;

    private final OnClick onClick;

    // =========================
    // Full Constructor
    // =========================

    public ContactsAdapter(
            List<EmergencyContact> list,
            OnRemove onRemove,
            OnClick onClick
    ) {

        this.list = list;

        this.onRemove = onRemove;

        this.onClick = onClick;
    }

    // =========================
    // Old Constructor Support
    // =========================

    public ContactsAdapter(
            List<EmergencyContact> list,
            OnRemove onRemove
    ) {

        this.list = list;

        this.onRemove = onRemove;

        this.onClick = null;
    }

    // =========================
    // ViewHolder
    // =========================

    static class VH
            extends RecyclerView.ViewHolder {

        ItemContactBinding binding;

        public VH(
                ItemContactBinding binding
        ) {

            super(binding.getRoot());

            this.binding = binding;
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(
            @NonNull ViewGroup parent,
            int viewType
    ) {

        ItemContactBinding binding =
                ItemContactBinding.inflate(

                        LayoutInflater.from(
                                parent.getContext()
                        ),

                        parent,

                        false
                );

        return new VH(binding);
    }

    @Override
    public void onBindViewHolder(
            @NonNull VH holder,
            int position
    ) {

        EmergencyContact c =
                list.get(position);

        // =========================
        // Set Contact Data
        // =========================

        holder.binding.tvContactName.setText(
                c.name
        );

        holder.binding.tvContactPhone.setText(
                c.phone
        );

        // =========================
        // Remove Contact
        // =========================

        holder.binding.btnRemoveContact
                .setOnClickListener(v -> {

                    if (onRemove != null) {

                        onRemove.onRemove(
                                holder.getAdapterPosition()
                        );
                    }
                });

        // =========================
        // Open Tracking
        // =========================

        holder.itemView.setOnClickListener(v -> {

            if (onClick != null) {

                onClick.onClick(
                        holder.getAdapterPosition()
                );
            }
        });
    }

    @Override
    public int getItemCount() {

        return list.size();
    }
}