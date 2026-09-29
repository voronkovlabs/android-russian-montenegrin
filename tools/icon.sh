#!/usr/bin/env bash
#
# Пересобирает иконку приложения из исходного рисунка владельца.
#
#     bash tools/icon.sh tools/icon-source.webp
#
# Что тут происходит и почему именно так — в CLAUDE.md, раздел «Иконка».
# Коротко: слой адаптивной иконки 108dp, а показывает лаунчер центральные 72dp и
# режет их своей маской — кругом, скруглённым квадратом, чем угодно.
#
# Рисунок **не обрезается в круг** и кладётся квадратом в 85dp по центру. Это
# больше окна в 72dp, значит маска какой бы ни была, упрётся в рисунок, а не в
# пустоту: тёмных углов не бывает вовсе. И это меньше слоя в 108dp, значит от
# рисунка срезается немногое — при полном заполнении слоя первым уезжает верх
# шапки, пробовали.
#
# Pillow в проекте нет намеренно: ставить пакет в систему ради одной картинки
# незачем, а ffmpeg тут и так есть.
set -euo pipefail

SRC="${1:-tools/icon-source.webp}"
FF="${FFMPEG:-D:/Projects/_git/roop-unleashed/installer/installer_files/ffmpeg/bin/ffmpeg.exe}"
RES="src/app/src/main/res"

# плотность | слой, px | рисунок, px (85/108 от слоя)
for pair in "mdpi 108 85" "hdpi 162 128" "xhdpi 216 170" "xxhdpi 324 255" \
            "xxxhdpi 432 340"; do
  set -- $pair
  out="$RES/mipmap-$1/ic_launcher_foreground.png"
  off=$(( ($2 - $3) / 2 ))
  "$FF" -hide_banner -loglevel error -y -i "$SRC" \
    -vf "scale=$3:$3,format=rgba,pad=$2:$2:$off:$off:color=black@0" \
    -frames:v 1 "$out"
  printf '%-8s слой %3d, рисунок %3d — %s\n' "$1" "$2" "$3" "$out"
done
