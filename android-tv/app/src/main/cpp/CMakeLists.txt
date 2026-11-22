cmake_minimum_required(VERSION 3.22.1)
project(viewerlog C)

# Raises the minimum logcat priority of the process before libmpv starts.
add_library(viewerlog SHARED viewerlog.c)
target_link_libraries(viewerlog log dl)
