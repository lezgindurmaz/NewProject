package com.droidsu.manager;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.util.ArrayList;
import java.util.List;

public class SuperUserFragment extends Fragment {
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_superuser, container, false);

        ListView listView = view.findViewById(R.id.appsListView);
        PackageManager pm = getActivity().getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        List<ApplicationInfo> filteredApps = new ArrayList<>();

        for (ApplicationInfo app : apps) {
            if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                filteredApps.add(app);
            }
        }

        listView.setAdapter(new ArrayAdapter<ApplicationInfo>(getContext(), R.layout.item_app, filteredApps) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_app, parent, false);
                }
                ApplicationInfo app = getItem(position);
                TextView name = convertView.findViewById(R.id.appName);
                TextView pkg = convertView.findViewById(R.id.packageName);
                CheckBox cb = convertView.findViewById(R.id.appCheckbox);

                name.setText(app.loadLabel(pm));
                pkg.setText(app.packageName);
                cb.setChecked(WhitelistUtils.isAllowed(app.packageName));

                cb.setOnClickListener(v -> {
                    WhitelistUtils.addApp(app.packageName);
                });

                return convertView;
            }
        });

        return view;
    }
}
