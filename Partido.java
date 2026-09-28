package dza.folbol.BLABONGO;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class Partido {
    public Attributes attributes;

    public static class Attributes {
        public String diary_description;
        public String diary_hour;
        public String date_diary;          // <--- NUEVO (ya lo contiene el JSON)
        public Embeds embeds;

        /** Devuelve la hora formateada para mostrar (HH:mm). */
        public String getHoraLocal() {
            if (diary_hour == null || diary_hour.isEmpty()) return "--:--";
            try {
                String limpia = diary_hour.replace("Z", "");
                if (limpia.contains(".")) {
                    limpia = limpia.substring(0, limpia.lastIndexOf("."));
                }
                SimpleDateFormat formatoEntrada;
                if (limpia.contains("T") || limpia.contains("-")) {
                    formatoEntrada = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault());
                } else {
                    if (limpia.split(":").length == 2) {
                        limpia += ":00";
                    }
                    formatoEntrada = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                }
                formatoEntrada.setTimeZone(TimeZone.getDefault());
                Date horaServidor = formatoEntrada.parse(limpia);
                SimpleDateFormat formatoSalida = new SimpleDateFormat("HH:mm", Locale.getDefault());
                formatoSalida.setTimeZone(TimeZone.getDefault());
                return formatoSalida.format(horaServidor);
            } catch (Exception e) {
                if (diary_hour.contains(":") && diary_hour.length() >= 5) {
                    if (diary_hour.contains("T")) {
                        return diary_hour.split("T")[1].substring(0, 5);
                    }
                    return diary_hour.substring(0, 5);
                }
                return "--:--";
            }
        }

        /**
         * Calcula los milisegundos de la hora de inicio del partido.
         * Usa date_diary + diary_hour y la zona horaria local.
         */
        public long getStartTimeMillis() {
            if (date_diary == null || date_diary.isEmpty() ||
                    diary_hour == null || diary_hour.isEmpty()) return 0;

            try {
                // Limpiar la hora (quitamos segundos y fracciones)
                String hora = diary_hour.replace("Z", "");
                if (hora.contains(".")) {
                    hora = hora.substring(0, hora.lastIndexOf("."));
                }
                if (hora.contains("T")) {
                    hora = hora.split("T")[1];
                }
                if (hora.split(":").length >= 2) {
                    hora = hora.substring(0, 5); // "HH:mm"
                } else {
                    return 0;
                }

                String fechaHora = date_diary + " " + hora + ":00"; // "yyyy-MM-dd HH:mm:ss"
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                sdf.setTimeZone(TimeZone.getDefault());
                Date date = sdf.parse(fechaHora);
                return date != null ? date.getTime() : 0;
            } catch (Exception e) {
                return 0;
            }
        }
    }

    public static class Embeds {
        public List<EmbedData> data;
    }

    public static class EmbedData {
        public AttributesEmbed attributes;
    }

    public static class AttributesEmbed {
        public String embed_name;
        public String embed_iframe;
    }

    public static void ordenarPorHora(List<Partido> lista) {
        if (lista == null || lista.isEmpty()) return;
        Collections.sort(lista, (p1, p2) -> {
            String hora1 = (p1.attributes != null && p1.attributes.diary_hour != null) ? p1.attributes.diary_hour : "9999";
            String hora2 = (p2.attributes != null && p2.attributes.diary_hour != null) ? p2.attributes.diary_hour : "9999";
            return hora1.compareTo(hora2);
        });
    }
}