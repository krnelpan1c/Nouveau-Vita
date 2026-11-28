package com.vitalauncher.app.recents;

interface IRecentsService {
    void destroy() = 16777114;
    List<String> getRecentPackages(int maxCount) = 1;
}

