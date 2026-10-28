#!/system/bin/sh
# Подменить базу раскладки лаунчера файлом $1 (лаунчер останавливается на время копирования).
# su -c sh swap-db.sh /data/local/tmp/x.db. Откат — тот же скрипт с сохранённой копией.
DB=/data/data/com.nothing.launcher/databases/launcher_4_by_8.db
[ -f "$1" ] || { echo "нет файла $1"; exit 1; }
am force-stop com.nothing.launcher
cat "$1" > $DB                      # cat, а не cp: владелец и метка SELinux у файла остаются прежними
rm -f $DB-journal
chown u0_a222:u0_a222 $DB
chcon u:object_r:privapp_data_file:s0:c512,c768 $DB
input keyevent HOME
ls -lZ $DB
