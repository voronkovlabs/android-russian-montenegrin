#!/usr/bin/env bash
#
# Пересобирает иконку приложения из исходного рисунка владельца.
#
#     bash tools/icon.sh tools/icon-source.webp
#
# Что тут происходит и почему именно так — в CLAUDE.md, раздел «Иконка».
# Коротко: слой адаптивной иконки 108dp, маска лаунчера гарантирует только
# центральные 72dp, рисунок садится кругом в 78dp. Квадратную картинку в этот
# круг лобовым кропом не втиснуть — срезает флаг в правом верхнем углу, — поэтому
# сам рисунок занимает около 85% круга, а ободок заполняется его же увеличенной
# размытой копией: небо, горы и море продолжаются наружу сами собой.
#
# Pillow в проекте нет намеренно: ставить пакет в систему ради одной картинки
# незачем, а ffmpeg тут и так есть.
set -euo pipefail

SRC="${1:-tools/icon-source.webp}"
FF="${FFMPEG:-D:/Projects/_git/roop-unleashed/installer/installer_files/ffmpeg/bin/ffmpeg.exe}"
RES="src/app/src/main/res"
MASTER="$(mktemp -u).png"

# Круг разом в 1248px: каждая плотность уменьшается уже из него, и край
# остаётся гладким — сглаживание считается один раз и на большом размере.
"$FF" -hide_banner -loglevel error -y -i "$SRC" -filter_complex \
  "[0:v]scale=1560:1560,crop=1248:1248,boxblur=44:3[bg];\
[0:v]scale=1060:1060[fg];\
[bg][fg]overlay=(W-w)/2:(H-h)/2,format=rgba,\
geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='if(lte(hypot(X-624,Y-624),623),255,0)'" \
  -frames:v 1 "$MASTER"

# плотность | слой, px | круг, px (78/108 от слоя)
for pair in "mdpi 108 78" "hdpi 162 117" "xhdpi 216 156" "xxhdpi 324 234" \
            "xxxhdpi 432 312"; do
  set -- $pair
  out="$RES/mipmap-$1/ic_launcher_foreground.png"
  off=$(( ($2 - $3) / 2 ))
  "$FF" -hide_banner -loglevel error -y -i "$MASTER" \
    -vf "scale=$3:$3,pad=$2:$2:$off:$off:color=black@0" -frames:v 1 "$out"
  printf '%-8s слой %3d, круг %3d — %s\n' "$1" "$2" "$3" "$out"
done

rm -f "$MASTER"
