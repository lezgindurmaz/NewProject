package com.droidsu.manager;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class ModulesFragment extends Fragment {
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_modules, container, false);

        Button installBtn = view.findViewById(R.id.installBundledBtn);
        RecyclerView recyclerView = view.findViewById(R.id.modulesRecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        installBtn.setOnClickListener(v -> {
            Toast.makeText(getContext(), "Installing modules...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                BundleUtils.installBundledModules(getContext());
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), "Modules installed successfully.", Toast.LENGTH_LONG).show();
                });
            }).start();
        });

        return view;
    }
}
