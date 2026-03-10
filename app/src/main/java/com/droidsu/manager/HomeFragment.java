package com.droidsu.manager;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class HomeFragment extends Fragment {
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);

        TextView statusText = view.findViewById(R.id.statusText);
        Button patchButton = view.findViewById(R.id.patchButton);

        statusText.setText("DroidSU v2.0-DroidSU Ready");

        patchButton.setOnClickListener(v -> {
            // Trigger boot.img picker
            ((MainActivity)requireActivity()).pickBootImage();
        });

        return view;
    }
}
