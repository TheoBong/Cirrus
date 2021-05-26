package dev.simplix.cirrus.spigot.converters;

import static de.exceptionflug.protocolize.api.util.ProtocolVersions.MINECRAFT_1_13;
import static de.exceptionflug.protocolize.api.util.ProtocolVersions.MINECRAFT_1_14;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import de.exceptionflug.protocolize.items.ItemStack;
import de.exceptionflug.protocolize.items.ItemType;
import dev.simplix.cirrus.spigot.util.ProtocolVersionUtil;
import dev.simplix.core.common.converter.Converter;
import dev.simplix.core.common.converter.Converters;
import dev.simplix.core.minecraft.spigot.util.ReflectionUtil;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.NonNull;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.chat.ComponentSerializer;
import net.querz.nbt.tag.*;
import org.bukkit.Material;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.material.MaterialData;

public class ProtocolizeItemStackConverter implements Converter<ItemStack, org.bukkit.inventory.ItemStack> {

  private static Class<?> craftItemStackClass;
  private static Class<?> nbtTagCompoundClass;
  private static Class<?> itemStackNMSClass;
  private static Method nmsCopyMethod;
  private static Method bukkitCopyMethod;

  static {
    try {
      craftItemStackClass = ReflectionUtil.getClass("{obc}.inventory.CraftItemStack");
      nbtTagCompoundClass = ReflectionUtil.getClass("{nms}.NBTTagCompound");
      itemStackNMSClass = ReflectionUtil.getClass("{nms}.ItemStack");
      nmsCopyMethod = craftItemStackClass.getMethod(
          "asNMSCopy",
          org.bukkit.inventory.ItemStack.class);
      bukkitCopyMethod = craftItemStackClass.getMethod("asBukkitCopy", itemStackNMSClass);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  @Override
  public org.bukkit.inventory.ItemStack convert(@NonNull ItemStack src) {
    if (src.getType() == ItemType.NO_DATA) {
      return null;
    }
    if (src.getType() == ItemType.AIR) {
      return new org.bukkit.inventory.ItemStack(Material.AIR);
    }
    MaterialData data = Converters.convert(src.getType(), MaterialData.class);
    org.bukkit.inventory.ItemStack out;
    if (ProtocolVersionUtil.serverProtocolVersion() < MINECRAFT_1_13) {
      out = new org.bukkit.inventory.ItemStack(
          data.getItemType(),
          src.getAmount(),
          src.getDurability(),
          data.getData());
    } else {
      out = new org.bukkit.inventory.ItemStack(
          data.getItemType(),
          src.getAmount(),
          src.getDurability());
    }

    if (src.getNBTTag() == null) {
      src.setNBTTag(new CompoundTag());
    }

    String textureHashToInsert = null;

    if (src.getNBTTag() instanceof CompoundTag) {
      CompoundTag tag = ((CompoundTag) src.getNBTTag());
      if (tag.containsKey("SkullOwner") && tag.get("SkullOwner") instanceof CompoundTag) {

        final CompoundTag skullOwnerTag = tag.getCompoundTag("SkullOwner");
        final Tag<?> propertiesRaw = skullOwnerTag.get("Properties");

        if (propertiesRaw instanceof CompoundTag) {
          try {
            final ListTag<CompoundTag> textures = (ListTag<CompoundTag>) ((CompoundTag) propertiesRaw)
                .getListTag("textures");
            textureHashToInsert = textures.get(0).getString("Value");
            tag.remove("SkullOwner");
          } catch (final Exception ignored) {
          }
        }
      }
    }

    writeDataToNbt(src);

    try {
      Object nmsItemStack = nmsCopyMethod.invoke(null, out);
      Method setTag = itemStackNMSClass.getMethod("setTag", nbtTagCompoundClass);
      setTag.invoke(nmsItemStack, Converters.convert(src.getNBTTag(), nbtTagCompoundClass));
      final org.bukkit.inventory.ItemStack itemStack = (org.bukkit.inventory.ItemStack) bukkitCopyMethod
          .invoke(null, nmsItemStack);

      if (textureHashToInsert == null) {
        return itemStack;
      }
      final SkullMeta meta = (SkullMeta) itemStack.getItemMeta();
      final GameProfile profile = new GameProfile(UUID.randomUUID(), "");
      profile.getProperties().put("textures", new Property("textures", textureHashToInsert));

      try {
        Method metaSetProfileMethod = meta
            .getClass()
            .getDeclaredMethod("setProfile", GameProfile.class);
        metaSetProfileMethod.setAccessible(true);
        metaSetProfileMethod.invoke(meta, profile);
      } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException reflectiveOperationException) {
        // if in an older API where there is no setProfile method,
        // we set the profile field directly.
        try {
          Field profileField = meta.getClass().getDeclaredField("profile");
          profileField.setAccessible(true);
          profileField.set(meta, profile);

        } catch (NoSuchFieldException | IllegalAccessException ignored) {
        }
      }
      itemStack.setItemMeta(meta);
      return itemStack;
    } catch (final Exception exception) {
      exception.printStackTrace(); // Setting nbt to nms item is also pain in the ass
    }

    return out;
  }

  private void writeDataToNbt(@NonNull ItemStack stack) {
    if (stack.getDisplayName() != null) {
      if (ProtocolVersionUtil.serverProtocolVersion() >= MINECRAFT_1_13) {
        ((CompoundTag) stack.getNBTTag()).put("Damage", new IntTag(stack.getDurability()));
        setDisplayNameTag(
            (CompoundTag) stack.getNBTTag(),
            ComponentSerializer.toString(stack.getDisplayNameComponents()));
      } else {
        setDisplayNameTag(
            (CompoundTag) stack.getNBTTag(),
            TextComponent.toLegacyText(stack.getDisplayNameComponents()));
      }
    }

    if (stack.getLoreComponents() != null) {
      setLoreTag(
          (CompoundTag) stack.getNBTTag(),
          stack.getLoreComponents(),
          ProtocolVersionUtil.serverProtocolVersion());
    }
  }

  private void setDisplayNameTag(@NonNull CompoundTag nbtData, @NonNull String name) {
    if (name == null) {
      return;
    }
    CompoundTag display = (CompoundTag) nbtData.get("display");
    if (display == null) {
      display = new CompoundTag();
    }
    final StringTag tag = new StringTag(name);
    display.put("Name", tag);
    nbtData.put("display", display);
  }

  private void setLoreTag(
      @NonNull CompoundTag nbtData,
      @NonNull List<BaseComponent[]> lore,
      int protocolVersion) {
    if (lore == null) {
      return;
    }
    CompoundTag display = (CompoundTag) nbtData.get("display");
    if (display == null) {
      display = new CompoundTag();
    }
    if (protocolVersion < MINECRAFT_1_14) {
      final ListTag<StringTag> tag = new ListTag<>(StringTag.class);
      tag.addAll(lore.stream().map(i -> new StringTag(TextComponent.toLegacyText(i))).collect(
          Collectors.toList()));
      display.put("Lore", tag);
      nbtData.put("display", display);
    } else {
      final ListTag<StringTag> tag = new ListTag<>(StringTag.class);
      tag.addAll(lore.stream().map(components -> {
        for (BaseComponent component : components) {
          if (!component.isItalic()) {
            component.setItalic(false);
          }
        }
        return new StringTag(ComponentSerializer.toString(components));
      }).collect(Collectors.toList()));
      display.put("Lore", tag);
      nbtData.put("display", display);
    }
  }

}
