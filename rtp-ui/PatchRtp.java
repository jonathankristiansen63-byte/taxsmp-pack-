import java.nio.file.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Changes only GUI entry points and installs their listener. */
public final class PatchRtp implements Opcodes {
   public static void main(String[] args) throws Exception {
      Path root = Path.of(args[0]);
      int menus = 0, registrations = 0;
      for (String name : new String[]{"FoliaRTP", "gui/RTPGui", "gui/RegionGui", "gui/NetherRegionGui"}) {
         Path path = root.resolve("dev/foliartp/" + name + ".class");
         ClassNode type = new ClassNode();
         new ClassReader(Files.readAllBytes(path)).accept(type, 0);
         for (MethodNode method : type.methods) {
            if (name.equals("FoliaRTP") && method.name.equals("onEnable")) {
               for (AbstractInsnNode instruction : method.instructions.toArray()) {
                  if (instruction.getOpcode() != RETURN) continue;
                  InsnList hook = new InsnList();
                  hook.add(new VarInsnNode(ALOAD, 0));
                  hook.add(new MethodInsnNode(INVOKESTATIC, "dev/foliartp/gui/ArtworkMenus", "register", "(Ldev/foliartp/FoliaRTP;)V", false));
                  method.instructions.insertBefore(instruction, hook);
                  registrations++;
               }
            } else if (!name.equals("FoliaRTP") && method.name.equals("open")
               && method.desc.equals("(Lorg/bukkit/entity/Player;Ldev/foliartp/FoliaRTP;)V")) {
               method.instructions.clear();
               method.tryCatchBlocks.clear();
               if (method.localVariables != null) method.localVariables.clear();
               method.instructions.add(new VarInsnNode(ALOAD, 0));
               method.instructions.add(new VarInsnNode(ALOAD, 1));
               method.instructions.add(new LdcInsnNode(name.equals("gui/RTPGui") ? "root" : name.equals("gui/RegionGui") ? "overworld" : "nether"));
               method.instructions.add(new MethodInsnNode(INVOKESTATIC, "dev/foliartp/gui/ArtworkMenus", "open", "(Lorg/bukkit/entity/Player;Ldev/foliartp/FoliaRTP;Ljava/lang/String;)V", false));
               method.instructions.add(new InsnNode(RETURN));
               method.maxLocals = 2;
               menus++;
            }
         }
         ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
         type.accept(writer);
         Files.write(path, writer.toByteArray());
      }
      if (menus != 3 || registrations != 1) throw new IllegalStateException("Unexpected RTP input: " + menus + " menus, " + registrations + " startup hooks");
      System.out.println("Patched three GUI entry points and one startup hook; teleport and queue classes preserved.");
   }
}
