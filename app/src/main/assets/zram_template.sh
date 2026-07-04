#!/system/bin/sh
# ZRAM configuration - Q25 Toolbox
#
# Re-initialise zram0 with the configured size, compression algorithm and
# swappiness at boot. (No Qualcomm post-boot wait - the Q25 is MediaTek.)
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done
sleep 5

swapoff /dev/block/zram0 2>/dev/null
echo 1 > /sys/block/zram0/reset
echo __ALGO__ > /sys/block/zram0/comp_algorithm
echo __SIZE_MB__m > /sys/block/zram0/disksize
mkswap /dev/block/zram0
swapon /dev/block/zram0

echo __SWAPPINESS__ > /proc/sys/vm/swappiness
