# ink music's own way to a USB DAC (usbaudio.c): plain C on Linux's usbfs, no libraries
cmake_minimum_required(VERSION 3.22)
project(inkusb C)
add_library(inkusb SHARED usbaudio.c)
target_compile_options(inkusb PRIVATE -O2 -Wall)
target_link_libraries(inkusb log m)
