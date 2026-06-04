package com.suraksha.ai.ui.friends;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.suraksha.ai.databinding.FragmentFriendsBinding;
import com.suraksha.ai.model.EmergencyContact;
import com.suraksha.ai.ui.settings.ContactsAdapter;
import com.suraksha.ai.ui.tracking.LiveTrackingActivity;
import com.suraksha.ai.utils.FirebaseLocationHelper;
import com.suraksha.ai.utils.PrefsManager;

import java.util.ArrayList;
import java.util.List;

public class FriendsFragment extends Fragment {

    private FragmentFriendsBinding binding;

    private final List<EmergencyContact> contacts = new ArrayList<>();

    private ContactsAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            ViewGroup container,
            Bundle savedInstanceState
    ) {

        binding = FragmentFriendsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(
            @NonNull View view,
            @Nullable Bundle savedInstanceState
    ) {

        super.onViewCreated(view, savedInstanceState);

        adapter = new ContactsAdapter(
                contacts,
                pos -> removeContact(pos),
                pos -> openTracking(pos)
        );

        binding.rvFriends.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvFriends.setAdapter(adapter);

        loadContacts();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadContacts();
    }

    // =========================
    // Load Contacts
    // =========================

    private void loadContacts() {

        contacts.clear();
        contacts.addAll(new PrefsManager(requireContext()).getContacts());

        if (binding != null) {
            adapter.notifyDataSetChanged();
            binding.tvEmpty.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    // =========================
    // Remove Contact
    // =========================

    private void removeContact(int pos) {

        contacts.remove(pos);
        adapter.notifyItemRemoved(pos);
        new PrefsManager(requireContext()).saveContacts(contacts);
        binding.tvEmpty.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // =========================
    // Open Tracking
    // If trackingId is missing,
    // try phone lookup first
    // =========================

    private void openTracking(int pos) {

        EmergencyContact contact = contacts.get(pos);

        // If we already have tracking ID, open directly
        if (contact.trackingId != null && !contact.trackingId.isEmpty()) {
            launchTracking(contact.name, contact.trackingId);
            return;
        }

        // No tracking ID — try to look up by phone number
        if (contact.phone == null || contact.phone.isEmpty()) {
            Toast.makeText(requireContext(),
                    "No tracking ID or phone number for this contact",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(requireContext(),
                "Looking up " + contact.name + "...",
                Toast.LENGTH_SHORT).show();

        FirebaseLocationHelper.lookupByPhone(contact.phone,
                new FirebaseLocationHelper.TrackingIdCallback() {

                    @Override
                    public void onFound(String trackingId) {

                        if (getActivity() == null) return;

                        // Save the found tracking ID so we don't need to look up again
                        contact.trackingId = trackingId;
                        new PrefsManager(requireContext()).saveContacts(contacts);

                        getActivity().runOnUiThread(() ->
                                launchTracking(contact.name, trackingId));
                    }

                    @Override
                    public void onNotFound() {

                        if (getActivity() == null) return;

                        getActivity().runOnUiThread(() ->
                                Toast.makeText(requireContext(),
                                        contact.name + " hasn't set up their phone number yet. " +
                                        "Ask them to open Settings in Suraksha AI and save their number.",
                                        Toast.LENGTH_LONG).show()
                        );
                    }
                });
    }

    private void launchTracking(String name, String trackingId) {

        Intent i = new Intent(requireContext(), LiveTrackingActivity.class);
        i.putExtra("trackingId", trackingId);
        i.putExtra("name", name);
        startActivity(i);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
