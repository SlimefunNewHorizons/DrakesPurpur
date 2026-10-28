#!/usr/bin/env python3
"""Porta un addon de Slimefun al ecosistema DrakesCraft (Paper/Purpur 1.21.11, Java 21).

QUE HACE Y POR QUE

El core de DrakesCraft repaqueto Slimefun a `com.github.drakescraft_labs`. Cualquier addon de
fuera compila contra `io.github.thebusybiscuit` y por tanto no encuentra nada. Comprobado leyendo
las declaraciones `package` del propio core, el mapeo real es:

    io.github.thebusybiscuit        ->  com.github.drakescraft_labs
    me.mrCookieSlime.Slimefun       ->  com.github.drakescraft_labs.slimefun4.legacy
    me.mrCookieSlime.CSCoreLibPlugin                               (se queda igual)
    io.github.bakedlibs.dough  ->  com.github.drakescraft_labs.slimefun4.libraries.dough

Ojo con la segunda: el segmento `Slimefun` DESAPARECE. En el arbol de ficheros la carpeta sigue
llamandose `legacy/Slimefun/`, pero dentro los ficheros declaran `legacy.Objects...` y
`legacy.api...`, sin ese trozo. Guiarse por las carpetas y no por los `package` deja un prefijo
que no existe y el addon no compila.

La tercera tambien engana: CSCoreLibPlugin se movio de carpeta pero NO se repaqueto, asi que sus
clases siguen siendo `me.mrCookieSlime.CSCoreLibPlugin...` de verdad.

Y la cuarta es la mas traicionera: el core compila contra `dev.drake.dough`, pero al empaquetar
el shade la relocaliza a `com.github.drakescraft_labs.slimefun4.libraries.dough`. Lo que ve un
addon en el jar publicado es ESA, no `dev.drake.dough` ni la original de bakedlibs. Se comprobo
en los imports de addons que ya funcionan.

Ademas se reescribe el pom para compilar contra el maven de DrakesCraft y para Java 21.

NO se toca el paquete propio del addon ni sus nombres de clase: eso es del autor original y
mantenerlo intacto hace que las actualizaciones de arriba sigan siendo legibles.

Uso:  python3 portar.py <carpeta-del-repo> [--escribir]
      Sin --escribir solo informa de lo que haria.
"""
import pathlib
import re
import sys

PREFIJO_VIEJO = "io.github.thebusybiscuit"
PREFIJO_NUEVO = "com.github.drakescraft_labs"

# Orden importante: el mapeo de me.mrCookieSlime.Slimefun tiene que ir ANTES que cualquier regla
# mas corta sobre me.mrCookieSlime, o se aplicaria la generica y quedaria a medias.
REMAPEOS = [
    ("io.github.thebusybiscuit", "com.github.drakescraft_labs"),
    ("me.mrCookieSlime.Slimefun", "com.github.drakescraft_labs.slimefun4.legacy"),
    ("io.github.bakedlibs.dough", "com.github.drakescraft_labs.slimefun4.libraries.dough"),
    ("dev.drake.dough", "com.github.drakescraft_labs.slimefun4.libraries.dough"),
]

# Segunda pasada, para addons de antes de 2021.
#
# Aquellos usaban clases que no solo se movieron de paquete: unas cambiaron de nombre (Category ->
# ItemGroup) y otras se quedaron como compatibilidad en otra ruta. El primer remapeo las manda a
# `legacy.*`, donde NO estan, asi que hay que redirigirlas una a una.
#
# Se aplica DESPUES del remapeo general, sobre los nombres ya reescritos.
API_ANTIGUA = [
    # cscorelib2.inventory se repartio entre DOS destinos, asi que no vale una regla de paquete:
    # ChestMenu y ClickAction se quedaron en CSCoreLibPlugin (que no se repaqueto) y solo InvUtils
    # acabo en dough. Comprobado listando ambos paquetes dentro del jar del core.
    ("com.github.drakescraft_labs.slimefun4.legacy.cscorelib2.inventory.ChestMenu",
     "me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu"),
    ("com.github.drakescraft_labs.slimefun4.legacy.cscorelib2.inventory.ClickAction",
     "me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction"),
    # La API de protecciones de dough renombro la clase entera. Se sustituye el nombre a secas
    # para pillar de una vez el import y las constantes (ProtectableAction.ATTACK_PLAYER y demas):
    # el identificador no aparece con ningun otro significado.
    ("ProtectableAction", "Interaction"),
    # InfinityLib reordeno sus paquetes entre la 1.2 y la 1.3, que es la que usamos.
    ("io.github.mooy1.infinitylib.AbstractAddon",
     "io.github.mooy1.infinitylib.core.AbstractAddon"),
    ("io.github.mooy1.infinitylib.bstats.", "io.github.mooy1.infinitylib.metrics."),
    (".slimefun4.legacy.Lists.RecipeType", ".slimefun4.api.recipes.RecipeType"),
    (".slimefun4.legacy.Objects.Category", ".slimefun4.api.items.Category"),
    (".slimefun4.legacy.Objects.SlimefunItem.SlimefunItem", ".slimefun4.api.items.SlimefunItem"),
    (".slimefun4.legacy.api.SlimefunItemStack", ".slimefun4.api.items.SlimefunItemStack"),
    (".slimefun4.legacy.cscorelib2.item.CustomItem", ".slimefun4.api.items.CustomItem"),
    (".slimefun4.legacy.cscorelib2.config.Config", ".slimefun4.libraries.dough.config.Config"),
    (".slimefun4.legacy.cscorelib2.", ".slimefun4.libraries.dough."),
]

# Dependencias de un addon sobre OTRO addon.
#
# Varios addons usan objetos de sus vecinos en las recetas, y tiran de la version de upstream. Esa
# esta compilada contra los paquetes originales de Slimefun, asi que arrastra al classpath tipos
# como `me.mrCookieSlime.Slimefun.api.SlimefunItemStack` que aqui no existen. El sintoma engana:
# los errores salen en NUESTRO codigo ("cannot access ...", "incompatible types") aunque el
# problema este en el jar del vecino.
#
# Hay que apuntar a los ports de DrakesCraft, que ademas son los que corren en produccion: si se
# compila contra el de upstream, los IDs de objeto pueden no coincidir con los del servidor.
#
# Clave: (groupId, artifactId) de upstream.  Valor: los tres de nuestro port.
DEPS_ADDON = {
    ("com.github.GallowsDove", "FoxyMachines"):
        ("com.github.drakescraft_labs", "FoxyMachines-drake", "1.21.11-Drake.1"),
    ("com.github.Mooy1", "InfinityLib"):
        ("io.github.mooy1", "InfinityLib", "1.3.10-Drake-1.21.11"),
    ("com.github.SlimefunGuguProject", "InfinityLib"):
        ("io.github.mooy1", "InfinityLib", "1.3.10-Drake-1.21.11"),
}

MAVEN_DRAKE = """        <repository>
            <id>drakescraft-labs-maven</id>
            <url>https://maven.drakescraft.cl/</url>
        </repository>
"""

DEP_DRAKE = """        <dependency>
            <groupId>com.github.drakescraft_labs</groupId>
            <artifactId>slimefun-core</artifactId>
            <version>11.0-Drake-1.21.11-SNAPSHOT</version>
            <scope>provided</scope>
        </dependency>
"""


def _remapear(linea):
    """Aplica todos los remapeos de paquete a una linea."""
    for viejo, nuevo in REMAPEOS:
        linea = re.sub(r"\b" + re.escape(viejo) + r"\b", nuevo, linea)
    # Segunda pasada: las clases de la API antigua que se movieron o renombraron.
    for viejo, nuevo in API_ANTIGUA:
        linea = linea.replace(viejo, nuevo)
    return linea


def portar_fuentes(repo, escribir):
    """Reescribe el prefijo de Slimefun en el codigo fuente.

    Se miran tambien los .kt: algun addon esta escrito en Kotlin, y ahi los imports se declaran
    igual que en Java, asi que el mismo remapeo vale. Lo unico que cambia es que en Kotlin la
    linea `package` no lleva punto y coma, cosa que la comprobacion de mas abajo ya tolera porque
    solo mira como empieza.
    """
    tocados = 0
    for f in sorted(repo.rglob("*.java")) + sorted(repo.rglob("*.kt")):
        if "/target/" in str(f) or "/build/" in str(f):
            continue
        texto = f.read_text(encoding="utf-8", errors="replace")
        # Se ancla en el prefijo completo para no pillar por accidente un paquete propio del
        # addon que empiece igual.
        # El namespace propio de algunos addons también comienza por el prefijo histórico
        # (por ejemplo SlimefunOreChunks). Su declaración package forma parte de la identidad
        # del plugin y debe conservarse; sólo se migran imports y referencias de API.
        nuevo = "\n".join(
            linea if linea.lstrip().startswith("package ") else _remapear(linea)
            for linea in texto.split("\n")
        )
        if nuevo != texto:
            tocados += 1
            if escribir:
                f.write_text(nuevo, encoding="utf-8")
    return tocados


def _sustituir_dependencia_slimefun(texto):
    """Cambia por la nuestra cualquier <dependency> cuyo artifactId hable de Slimefun.

    Se localizan los bloques completos <dependency>...</dependency> y se mira dentro, porque el
    contenido varia mucho de un addon a otro: unos llevan <exclusions>, otros <classifier>, y
    un patron unico no los cubre todos.
    """
    salida = []
    pos = 0
    sustituidas = 0

    while True:
        ini = texto.find("<dependency>", pos)
        if ini == -1:
            salida.append(texto[pos:])
            break
        fin = texto.find("</dependency>", ini)
        if fin == -1:
            salida.append(texto[pos:])
            break
        fin += len("</dependency>")

        bloque = texto[ini:fin]
        artifact = re.search(r"<artifactId>([^<]*)</artifactId>", bloque)
        if artifact and "slimefun" in artifact.group(1).lower():
            salida.append(texto[pos:ini])
            salida.append(DEP_DRAKE.strip())
            sustituidas += 1
        else:
            salida.append(texto[pos:fin])
        pos = fin

    return "".join(salida), sustituidas


def _sustituir_dependencias_de_addon(texto):
    """Apunta a los ports de DrakesCraft las dependencias sobre otros addons.

    Se reescriben los tres campos a la vez -- groupId, artifactId y version -- porque en varios
    casos cambian los tres, y dejar uno viejo resuelve un artefacto que no existe.
    """
    sustituidas = 0
    for (grupo, artefacto), (g2, a2, v2) in DEPS_ADDON.items():
        patron = re.compile(
            r"<groupId>" + re.escape(grupo) + r"</groupId>(\s*)"
            r"<artifactId>" + re.escape(artefacto) + r"</artifactId>(\s*)"
            r"<version>[^<]*</version>")
        texto, n = patron.subn(
            f"<groupId>{g2}</groupId>\\1<artifactId>{a2}</artifactId>\\2<version>{v2}</version>",
            texto)
        sustituidas += n
    return texto, sustituidas


def _fuentes_del_repo(repo):
    """Todo el codigo del repo en un solo texto, para buscar dependencias implicitas."""
    trozos = []
    for f in repo.rglob("*.java"):
        if "/target/" not in str(f):
            trozos.append(f.read_text(encoding="utf-8", errors="replace"))
    return "\n".join(trozos)


def portar_pom(repo, escribir):
    """Apunta el build al maven de DrakesCraft y a Java 21."""
    pom = repo / "pom.xml"
    if not pom.exists():
        return "sin pom.xml (build de Gradle: hay que portarlo a mano)"

    texto = pom.read_text(encoding="utf-8")
    antes = texto

    # Java 21: los addons viejos declaran 8, 11 o 16 y no compilan contra el core actual.
    texto = re.sub(r"<maven\.compiler\.(source|target)>[\d.]+</maven\.compiler\.\1>",
                   lambda m: f"<maven.compiler.{m.group(1)}>21</maven.compiler.{m.group(1)}>",
                   texto)
    texto = re.sub(r"<release>\d+</release>", "<release>21</release>", texto)

    # Paper: los addons viejos apuntan a 1.16/1.18 y al repo antiguo de PaperMC, que hoy
    # responde 403. Sin esto ni siquiera se resuelven las dependencias.
    #
    # Se fija 1.21.11, la MISMA que corre en produccion. Con una anterior el codigo compila y
    # luego revienta al arrancar con NoSuchFieldError, porque los enums renombrados se resuelven
    # por nombre en tiempo de ejecucion: el fallo se traslada del build al servidor.
    PAPER = "1.21.11-R0.1-SNAPSHOT"
    texto = re.sub(r"<artifactId>paper-api</artifactId>\s*<version>[^<]*</version>",
                   f"<artifactId>paper-api</artifactId>\n            "
                   f"<version>{PAPER}</version>", texto)
    # spigot-api pasa a paper-api: el servidor es Purpur, y hay addons que usan metodos que solo
    # existen en Paper (por ejemplo Block#getState(boolean)). Con spigot-api no compilan.
    texto = re.sub(r"<groupId>org\.spigotmc</groupId>(\s*)<artifactId>spigot-api</artifactId>"
                   r"\s*<version>[^<]*</version>",
                   f"<groupId>io.papermc.paper</groupId>\\1<artifactId>paper-api</artifactId>\n"
                   f"            <version>{PAPER}</version>", texto)
    # Los addons de antes de 2020 dependen del artefacto ancestral org.bukkit:bukkit, anterior
    # incluso a spigot-api. Es el que mas engana de todos: resuelve sin problema desde el repo de
    # Spigot, asi que el build parece sano, pero deja en el classpath una API de 1.15 donde media
    # constante moderna no existe todavia. El sintoma es un "cannot find symbol" sobre algo que si
    # esta en paper-api (Enchantment.UNBREAKING, por ejemplo) y no lleva a ninguna parte hasta que
    # se mira que jar hay debajo.
    texto = re.sub(r"<groupId>org\.bukkit</groupId>(\s*)<artifactId>bukkit</artifactId>"
                   r"\s*<version>[^<]*</version>",
                   f"<groupId>io.papermc.paper</groupId>\\1<artifactId>paper-api</artifactId>\n"
                   f"            <version>{PAPER}</version>", texto)
    # El groupId de paper-api cambio de com.destroystokyo.paper a io.papermc.paper.
    texto = texto.replace("<groupId>com.destroystokyo.paper</groupId>",
                          "<groupId>io.papermc.paper</groupId>")
    texto = texto.replace("https://papermc.io/repo/repository/maven-public/",
                          "https://repo.papermc.io/repository/maven-public/")
    # repo.destroystokyo.com murio; lo que colgaba de ahi vive hoy en jitpack o en papermc.
    texto = texto.replace("https://repo.destroystokyo.com/repository/maven-public/",
                          "https://repo.papermc.io/repository/maven-public/")

    # Lombok anterior a 1.18.30 no compila con Java 21: revienta con IllegalAccessError contra
    # jdk.compiler. Se sube a una version que si lo soporta.
    texto = re.sub(r"(<artifactId>lombok</artifactId>\s*<version>)[^<]*(</version>)",
                   r"\g<1>1.18.34\g<2>", texto)

    # La dependencia de Slimefun pasa a la nuestra, sea cual sea la que traiga.
    #
    # Se recorre bloque a bloque en vez de con un patron: hay poms cuyo <dependency> lleva
    # <exclusions> u otros elementos detras del <scope>, y un patron rigido los dejaba pasar.
    # El resultado era tener DOS dependencias de Slimefun y un fallo de resolucion.
    texto, sustituidas = _sustituir_dependencia_slimefun(texto)
    texto, _ = _sustituir_dependencias_de_addon(texto)
    if sustituidas == 0 and "slimefun-core" not in texto:
        # Solo si no dependia de Slimefun por pom Y no se la hemos puesto ya. Sin la segunda
        # comprobacion, ejecutar el portador dos veces dejaba la dependencia duplicada y el
        # build fallaba por conflicto.
        texto = texto.replace("<dependencies>", "<dependencies>\n" + DEP_DRAKE, 1)

    # Paper dejo de traer commons-lang en 1.21. Los addons que lo usaban compilaban "gratis"
    # y ahora no encuentran el paquete, asi que se declara explicitamente.
    if "org.apache.commons.lang" in _fuentes_del_repo(repo) and "commons-lang</artifactId>" not in texto:
        texto = texto.replace("<dependencies>", """<dependencies>
        <dependency>
            <groupId>commons-lang</groupId>
            <artifactId>commons-lang</artifactId>
            <version>2.6</version>
            <scope>provided</scope>
        </dependency>
""", 1)

    # El maven-compiler-plugin viejo (3.7 es de 2018) no procesa bien las anotaciones con Java 21:
    # Lombok deja de generar los getters y salen decenas de "cannot find symbol" que parecen
    # errores del addon y no lo son.  # compiler-plugin-modernizado
    texto = re.sub(r"(<artifactId>maven-compiler-plugin</artifactId>\s*<version>)[^<]*(</version>)",
                   r"\g<1>3.13.0\g<2>", texto)
    # Y su <source>/<target> propios pisan las properties de arriba, asi que se sustituyen por
    # <release>, que es lo que manda desde Java 9.
    texto = re.sub(r"<source>\d+</source>\s*<target>\d+</target>", "<release>21</release>", texto)

    # El maven-shade-plugin anterior a 3.5 no sabe leer bytecode de Java 21 (major 65) y falla
    # con IllegalArgumentException al empaquetar. Los addons viejos traen 3.2.x o 3.3.x.
    texto = re.sub(r"(<artifactId>maven-shade-plugin</artifactId>\s*<version>)[^<]*(</version>)",
                   r"\g<1>3.6.1\g<2>", texto)

    # paperlib se movio de coordenadas y de version.
    texto = re.sub(r"<groupId>io\.papermc</groupId>\s*<artifactId>paperlib</artifactId>\s*"
                   r"<version>[^<]*</version>",
                   "<groupId>io.papermc</groupId>\n            "
                   "<artifactId>paperlib</artifactId>\n            <version>1.0.8</version>",
                   texto)

    if "maven.drakescraft.cl/" not in texto:
        texto = texto.replace("<repositories>", "<repositories>\n" + MAVEN_DRAKE, 1)

    # paper-api tambien arrastra `net.md-5:bungeecord-chat`, que vive en el repo de PaperMC. Si el
    # pom no lo declaraba (porque compilaba contra spigot-api), hay que ponerlo: sin el, el build
    # muere en resolucion aunque el codigo este perfecto.  # repo-papermc-anadido
    if "repo.papermc.io" not in texto and "<repositories>" in texto:
        texto = texto.replace("<repositories>", """<repositories>
        <repository>
            <id>papermc</id>
            <url>https://repo.papermc.io/repository/maven-public/</url>
        </repository>
""", 1)

    # paper-api arrastra `com.mojang:brigadier`, que solo se publica en el repositorio de
    # Mojang. Sin declararlo, el build muere en resolucion de dependencias sin llegar a compilar
    # -- y el sintoma engana, porque el contador de errores de compilacion se queda en cero.
    if "libraries.minecraft.net" not in texto and "<repositories>" in texto:
        texto = texto.replace("<repositories>", """<repositories>
        <repository>
            <id>minecraft-libraries</id>
            <url>https://libraries.minecraft.net</url>
        </repository>
""", 1)

    # jitpack: aqui viven casi todas las dependencias sueltas de estos addons, y varios
    # apuntaban a repos que ya no responden.
    if "jitpack.io" not in texto and "<repositories>" in texto:
        texto = texto.replace("<repositories>", """<repositories>
        <repository>
            <id>jitpack.io</id>
            <url>https://jitpack.io</url>
        </repository>
""", 1)

    if texto == antes:
        return "el pom no necesito cambios (revisar a mano)"
    if escribir:
        pom.write_text(texto, encoding="utf-8")
    return "pom actualizado"


def portar_plugin_yml(repo, escribir):
    """Sube el api-version del plugin.yml a 1.21.

    Los addons cosechados declaran 1.14, 1.16 o 1.18. No impide que arranquen -- cualquier valor
    a partir de 1.13 evita el modo heredado -- pero con uno viejo Paper aplica reglas de
    compatibilidad que ya no hacen falta y ensucia el arranque con avisos. Poner la version real
    del servidor deja claro contra que se probo.

    Se cita entre comillas a proposito: sin ellas, YAML lee 1.21 como numero decimal, y un dia que
    toque poner 1.21.11 el formato cambiaria de tipo sin avisar.
    """
    cambiados = []
    for yml in repo.rglob("plugin.yml"):
        if "/target/" in str(yml) or "/build/" in str(yml):
            continue
        texto = yml.read_text(encoding="utf-8", errors="replace")
        nuevo = re.sub(r"^api-version:.*$", "api-version: '1.21'", texto, flags=re.MULTILINE)
        if nuevo != texto:
            cambiados.append(yml.name)
            if escribir:
                yml.write_text(nuevo, encoding="utf-8")
    return len(cambiados)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1

    repo = pathlib.Path(sys.argv[1]).resolve()
    escribir = "--escribir" in sys.argv
    if not repo.is_dir():
        print(f"no existe: {repo}")
        return 1

    java = [f for f in repo.rglob("*.java") if "/target/" not in str(f)]
    usan = sum(1 for f in java if PREFIJO_VIEJO in f.read_text(encoding="utf-8", errors="replace"))

    print(f"=== {repo.name} ===")
    print(f"  ficheros java: {len(java)}")
    print(f"  usan el prefijo de upstream: {usan}")
    print(f"  {'APLICANDO' if escribir else 'simulacion (usa --escribir)'}")

    tocados = portar_fuentes(repo, escribir)
    print(f"  fuentes reescritas: {tocados}")
    print(f"  {portar_pom(repo, escribir)}")
    print(f"  plugin.yml con api-version al dia: {portar_plugin_yml(repo, escribir)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
